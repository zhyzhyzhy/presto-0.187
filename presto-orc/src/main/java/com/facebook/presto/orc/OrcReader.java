/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.orc;

import com.facebook.presto.orc.memory.AbstractAggregatedMemoryContext;
import com.facebook.presto.orc.memory.AggregatedMemoryContext;
import com.facebook.presto.orc.metadata.CompressionKind;
import com.facebook.presto.orc.metadata.ExceptionWrappingMetadataReader;
import com.facebook.presto.orc.metadata.Footer;
import com.facebook.presto.orc.metadata.Metadata;
import com.facebook.presto.orc.metadata.MetadataReader;
import com.facebook.presto.orc.metadata.PostScript;
import com.facebook.presto.orc.metadata.PostScript.HiveWriterVersion;
import com.facebook.presto.orc.stream.OrcInputStream;
import com.facebook.presto.spi.type.Type;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.airlift.units.DataSize;
import org.joda.time.DateTimeZone;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static com.facebook.presto.orc.OrcDecompressor.createOrcDecompressor;
import static com.facebook.presto.orc.metadata.PostScript.MAGIC;
import static io.airlift.slice.SizeOf.SIZE_OF_BYTE;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static java.lang.Math.min;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

public class OrcReader
{
    public static final int MAX_BATCH_SIZE = 1024;

    private static final Logger log = Logger.get(OrcReader.class);

    private static final int CURRENT_MAJOR_VERSION = 0;
    private static final int CURRENT_MINOR_VERSION = 12;
    private static final int EXPECTED_FOOTER_SIZE = 16 * 1024;

    private final OrcDataSource orcDataSource;
    private final ExceptionWrappingMetadataReader metadataReader;
    private final DataSize maxMergeDistance;
    private final DataSize maxReadSize;
    private final DataSize maxBlockSize;
    private final HiveWriterVersion hiveWriterVersion;
    private final int bufferSize;
    private final Optional<OrcDecompressor> decompressor;
    private final Footer footer;
    private final Metadata metadata;

    private final Optional<OrcWriteValidation> writeValidation;

    // This is based on the Apache Hive ORC code
    public OrcReader(OrcDataSource orcDataSource, MetadataReader delegate, DataSize maxMergeDistance, DataSize maxReadSize, DataSize maxBlockSize)
            throws IOException
    {
        this(orcDataSource, delegate, maxMergeDistance, maxReadSize, maxBlockSize, Optional.empty());
    }

    OrcReader(OrcDataSource orcDataSource, MetadataReader delegate, DataSize maxMergeDistance, DataSize maxReadSize, DataSize maxBlockSize, Optional<OrcWriteValidation> writeValidation)
            throws IOException
    {
        orcDataSource = wrapWithCacheIfTiny(requireNonNull(orcDataSource, "orcDataSource is null"), maxMergeDistance);
        this.orcDataSource = orcDataSource;
        this.metadataReader = new ExceptionWrappingMetadataReader(orcDataSource.getId(), requireNonNull(delegate, "delegate is null"));
        this.maxMergeDistance = requireNonNull(maxMergeDistance, "maxMergeDistance is null");
        this.maxReadSize = requireNonNull(maxReadSize, "maxReadSize is null");
        this.maxBlockSize = requireNonNull(maxBlockSize, "maxBlockSize is null");

        this.writeValidation = requireNonNull(writeValidation, "writeValidation is null");

        //
        // Read the file tail:
        //
        // variable: Footer
        // variable: Metadata
        // variable: PostScript - contains length of footer and metadata
        // 3 bytes: file magic "ORC"
        // 1 byte: postScriptSize = PostScript + Magic

        // figure out the size of the file using the option or filesystem
        long size = orcDataSource.getSize();
        if (size <= 0) {
            throw new OrcCorruptionException(orcDataSource.getId(), "Invalid file size %s", size);
        }

        // Read the tail of the file
        byte[] buffer = new byte[toIntExact(min(size, EXPECTED_FOOTER_SIZE))];
        orcDataSource.readFully(size - buffer.length, buffer);

        // get length of PostScript - last byte of the file
        int postScriptSize = buffer[buffer.length - SIZE_OF_BYTE] & 0xff;

        // make sure this is an ORC file and not an RCFile or something else
        verifyOrcFooter(orcDataSource, postScriptSize, buffer);

        // decode the post script
        int postScriptOffset = buffer.length - SIZE_OF_BYTE - postScriptSize;
        PostScript postScript = metadataReader.readPostScript(buffer, postScriptOffset, postScriptSize);

        // verify this is a supported version
        checkOrcVersion(orcDataSource, postScript.getVersion());
        validateWrite(validation -> validation.getVersion().equals(postScript.getVersion()), "Unexpected version");

        this.bufferSize = toIntExact(postScript.getCompressionBlockSize());

        // check compression codec is supported
        CompressionKind compressionKind = postScript.getCompression();
        this.decompressor = createOrcDecompressor(orcDataSource.getId(), compressionKind, bufferSize);
        validateWrite(validation -> validation.getCompression() == compressionKind, "Unexpected compression");

        this.hiveWriterVersion = postScript.getHiveWriterVersion();

        int footerSize = toIntExact(postScript.getFooterLength());
        int metadataSize = toIntExact(postScript.getMetadataLength());

        // check if extra bytes need to be read
        Slice completeFooterSlice;
        int completeFooterSize = footerSize + metadataSize + postScriptSize + SIZE_OF_BYTE;
        if (completeFooterSize > buffer.length) {
            // allocate a new buffer large enough for the complete footer
            byte[] newBuffer = new byte[completeFooterSize];
            completeFooterSlice = Slices.wrappedBuffer(newBuffer);

            // initial read was not large enough, so read missing section
            orcDataSource.readFully(size - completeFooterSize, newBuffer, 0, completeFooterSize - buffer.length);

            // copy already read bytes into the new buffer
            completeFooterSlice.setBytes(completeFooterSize - buffer.length, buffer);
        }
        else {
            // footer is already in the bytes in buffer, just adjust position, length
            completeFooterSlice = Slices.wrappedBuffer(buffer, buffer.length - completeFooterSize, completeFooterSize);
        }

        // read metadata
        Slice metadataSlice = completeFooterSlice.slice(0, metadataSize);
        try (InputStream metadataInputStream = new OrcInputStream(orcDataSource.getId(), metadataSlice.getInput(), decompressor, new AggregatedMemoryContext())) {
            this.metadata = metadataReader.readMetadata(hiveWriterVersion, metadataInputStream);
        }

        // read footer
        Slice footerSlice = completeFooterSlice.slice(metadataSize, footerSize);
        try (InputStream footerInputStream = new OrcInputStream(orcDataSource.getId(), footerSlice.getInput(), decompressor, new AggregatedMemoryContext())) {
            this.footer = metadataReader.readFooter(hiveWriterVersion, footerInputStream);
        }

        validateWrite(validation -> validation.getMetadata().equals(footer.getUserMetadata()), "Unexpected metadata");
        validateWrite(validation -> validation.getColumnNames().equals(getColumnNames()), "Unexpected column names");
        validateWrite(validation -> validation.getRowGroupMaxRowCount() == footer.getRowsInRowGroup(), "Unexpected rows in group");
        if (writeValidation.isPresent()) {
            writeValidation.get().validateFileStatistics(orcDataSource.getId(), footer.getFileStats());
            writeValidation.get().validateStripeStatistics(orcDataSource.getId(), footer.getStripes(), metadata.getStripeStatsList());
        }
    }

    public List<String> getColumnNames()
    {
        return footer.getTypes().get(0).getFieldNames();
    }

    public Footer getFooter()
    {
        return footer;
    }

    public Metadata getMetadata()
    {
        return metadata;
    }

    public int getBufferSize()
    {
        return bufferSize;
    }

    public OrcRecordReader createRecordReader(Map<Integer, Type> includedColumns, OrcPredicate predicate, DateTimeZone hiveStorageTimeZone, AbstractAggregatedMemoryContext systemMemoryUsage)
            throws IOException
    {
        return createRecordReader(includedColumns, predicate, 0, orcDataSource.getSize(), hiveStorageTimeZone, systemMemoryUsage);
    }

    public OrcRecordReader createRecordReader(
            Map<Integer, Type> includedColumns,
            OrcPredicate predicate,
            long offset,
            long length,
            DateTimeZone hiveStorageTimeZone,
            AbstractAggregatedMemoryContext systemMemoryUsage)
            throws IOException
    {
        return new OrcRecordReader(
                requireNonNull(includedColumns, "includedColumns is null"),
                requireNonNull(predicate, "predicate is null"),
                footer.getNumberOfRows(),
                footer.getStripes(),
                footer.getFileStats(),
                metadata.getStripeStatsList(),
                orcDataSource,
                offset,
                length,
                footer.getTypes(),
                decompressor,
                footer.getRowsInRowGroup(),
                requireNonNull(hiveStorageTimeZone, "hiveStorageTimeZone is null"),
                hiveWriterVersion,
                metadataReader,
                maxMergeDistance,
                maxReadSize,
                maxBlockSize,
                footer.getUserMetadata(),
                systemMemoryUsage,
                writeValidation);
    }

    private static OrcDataSource wrapWithCacheIfTiny(OrcDataSource dataSource, DataSize maxCacheSize)
    {
        if (dataSource instanceof CachingOrcDataSource) {
            return dataSource;
        }
        if (dataSource.getSize() > maxCacheSize.toBytes()) {
            return dataSource;
        }
        DiskRange diskRange = new DiskRange(0, toIntExact(dataSource.getSize()));
        return new CachingOrcDataSource(dataSource, desiredOffset -> diskRange);
    }

    /**
     * Verify this is an ORC file to prevent users from trying to read text
     * files or RC files as ORC files.
     */
    // This is based on the Apache Hive ORC code
    private static void verifyOrcFooter(
            OrcDataSource source,
            int postScriptSize,
            byte[] buffer)
            throws IOException
    {
        int magicLength = MAGIC.length();
        if ((postScriptSize < (magicLength + 1)) || (postScriptSize >= buffer.length)) {
            throw new OrcCorruptionException(source.getId(), "Invalid postscript length %s", postScriptSize);
        }

        if (!MAGIC.equals(Slices.wrappedBuffer(buffer, buffer.length - 1 - magicLength, magicLength))) {
            // Old versions of ORC (0.11) wrote the magic to the head of the file
            byte[] headerMagic = new byte[magicLength];
            source.readFully(0, headerMagic);

            // if it isn't there, this isn't an ORC file
            if (!MAGIC.equals(Slices.wrappedBuffer(headerMagic))) {
                throw new OrcCorruptionException(source.getId(), "Invalid postscript");
            }
        }
    }

    /**
     * Check to see if this ORC file is from a future version and if so,
     * warn the user that we may not be able to read all of the column encodings.
     */
    // This is based on the Apache Hive ORC code
    private static void checkOrcVersion(OrcDataSource orcDataSource, List<Integer> version)
    {
        if (version.size() >= 1) {
            int major = version.get(0);
            int minor = 0;
            if (version.size() > 1) {
                minor = version.get(1);
            }

            if (major > CURRENT_MAJOR_VERSION || (major == CURRENT_MAJOR_VERSION && minor > CURRENT_MINOR_VERSION)) {
                log.warn("ORC file %s was written by a newer Hive version %s. This file may not be readable by this version of Hive (%s.%s).",
                        orcDataSource,
                        Joiner.on('.').join(version),
                        CURRENT_MAJOR_VERSION,
                        CURRENT_MINOR_VERSION);
            }
        }
    }

    private void validateWrite(Predicate<OrcWriteValidation> test, String messageFormat, Object... args)
            throws OrcCorruptionException
    {
        if (writeValidation.isPresent() && !test.test(writeValidation.get())) {
            throw new OrcCorruptionException(orcDataSource.getId(), "Write validation failed: " + messageFormat, args);
        }
    }

    static void validateFile(
            OrcWriteValidation writeValidation,
            OrcDataSource input,
            List<Type> types,
            DateTimeZone hiveStorageTimeZone,
            MetadataReader metadataReader)
            throws OrcCorruptionException
    {
        ImmutableMap.Builder<Integer, Type> readTypes = ImmutableMap.builder();
        for (int columnIndex = 0; columnIndex < types.size(); columnIndex++) {
            readTypes.put(columnIndex, types.get(columnIndex));
        }
        try {
            OrcReader orcReader = new OrcReader(input, metadataReader, new DataSize(1, MEGABYTE), new DataSize(8, MEGABYTE), new DataSize(16, MEGABYTE), Optional.of(writeValidation));
            try (OrcRecordReader orcRecordReader = orcReader.createRecordReader(readTypes.build(), OrcPredicate.TRUE, hiveStorageTimeZone, new AggregatedMemoryContext())) {
                while (orcRecordReader.nextBatch() >= 0) {
                    // ignored
                }
            }
        }
        catch (IOException e) {
            throw new OrcCorruptionException(e, input.getId(), "Validation failed");
        }
    }
}
