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

import com.facebook.presto.orc.metadata.CompressionKind;

import java.util.Optional;

public interface OrcDecompressor
{
    static Optional<OrcDecompressor> createOrcDecompressor(OrcDataSourceId orcDataSourceId, CompressionKind compression, int bufferSize)
            throws OrcCorruptionException
    {
        switch (compression) {
            case NONE:
                return Optional.empty();
            case ZLIB:
                return Optional.of(new OrcZlibDecompressor(orcDataSourceId, bufferSize));
            case SNAPPY:
                return Optional.of(new OrcSnappyDecompressor(orcDataSourceId, bufferSize));
            case ZSTD:
                return Optional.of(new OrcZstdDecompressor(orcDataSourceId, bufferSize));
            default:
                throw new OrcCorruptionException(orcDataSourceId, "Unknown compression type: " + compression);
        }
    }

    int decompress(byte[] input, int offset, int length, OutputBuffer output)
            throws OrcCorruptionException;

    interface OutputBuffer
    {
        byte[] initialize(int size);

        byte[] grow(int size);
    }
}
