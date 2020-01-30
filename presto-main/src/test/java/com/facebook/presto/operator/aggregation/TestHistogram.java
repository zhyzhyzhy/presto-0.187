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
package com.facebook.presto.operator.aggregation;

import com.facebook.presto.metadata.MetadataManager;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.BlockBuilderStatus;
import com.facebook.presto.spi.type.ArrayType;
import com.facebook.presto.spi.type.MapType;
import com.facebook.presto.spi.type.RowType;
import com.facebook.presto.spi.type.SqlTimestampWithTimeZone;
import com.facebook.presto.spi.type.StandardTypes;
import com.facebook.presto.spi.type.TimeZoneKey;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.testng.annotations.Test;

import java.util.Optional;

import static com.facebook.presto.block.BlockAssertions.createBooleansBlock;
import static com.facebook.presto.block.BlockAssertions.createDoublesBlock;
import static com.facebook.presto.block.BlockAssertions.createLongsBlock;
import static com.facebook.presto.block.BlockAssertions.createStringArraysBlock;
import static com.facebook.presto.block.BlockAssertions.createStringsBlock;
import static com.facebook.presto.metadata.FunctionKind.AGGREGATE;
import static com.facebook.presto.operator.OperatorAssertion.toRow;
import static com.facebook.presto.operator.aggregation.AggregationTestUtils.assertAggregation;
import static com.facebook.presto.operator.aggregation.Histogram.NAME;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.TimeZoneKey.getTimeZoneKey;
import static com.facebook.presto.spi.type.TimestampWithTimeZoneType.TIMESTAMP_WITH_TIME_ZONE;
import static com.facebook.presto.spi.type.TypeSignature.parseTypeSignature;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.facebook.presto.util.DateTimeZoneIndex.getDateTimeZone;
import static com.facebook.presto.util.StructuralTestUtil.mapBlockOf;
import static com.facebook.presto.util.StructuralTestUtil.mapType;

public class TestHistogram
{
    private static final MetadataManager metadata = MetadataManager.createTestMetadataManager();
    private static final TimeZoneKey TIME_ZONE_KEY = getTimeZoneKey("UTC");
    private static final DateTimeZone DATE_TIME_ZONE = getDateTimeZone(TIME_ZONE_KEY);

    @Test
    public void testSimpleHistograms()
            throws Exception
    {
        MapType mapType = mapType(VARCHAR, BIGINT);
        InternalAggregationFunction aggregationFunction = metadata.getFunctionRegistry().getAggregateFunctionImplementation(
                new Signature(NAME,
                        AGGREGATE,
                        mapType.getTypeSignature(),
                        parseTypeSignature(StandardTypes.VARCHAR)));
        assertAggregation(
                aggregationFunction,
                ImmutableMap.of("a", 1L, "b", 1L, "c", 1L),
                createStringsBlock("a", "b", "c"));

        mapType = mapType(BIGINT, BIGINT);
        aggregationFunction = metadata.getFunctionRegistry().getAggregateFunctionImplementation(
                new Signature(NAME,
                        AGGREGATE,
                        mapType.getTypeSignature(),
                        parseTypeSignature(StandardTypes.BIGINT)));
        assertAggregation(
                aggregationFunction,
                ImmutableMap.of(100L, 1L, 200L, 1L, 300L, 1L),
                createLongsBlock(100L, 200L, 300L));

        mapType = mapType(DOUBLE, BIGINT);
        aggregationFunction = metadata.getFunctionRegistry().getAggregateFunctionImplementation(
                new Signature(NAME,
                        AGGREGATE,
                        mapType.getTypeSignature(),
                        parseTypeSignature(StandardTypes.DOUBLE)));
        assertAggregation(
                aggregationFunction,
                ImmutableMap.of(0.1, 1L, 0.3, 1L, 0.2, 1L),
                createDoublesBlock(0.1, 0.3, 0.2));

        mapType = mapType(BOOLEAN, BIGINT);
        aggregationFunction = metadata.getFunctionRegistry().getAggregateFunctionImplementation(
                new Signature(NAME,
                        AGGREGATE,
                        mapType.getTypeSignature(),
                        parseTypeSignature(StandardTypes.BOOLEAN)));
        assertAggregation(
                aggregationFunction,
                ImmutableMap.of(true, 1L, false, 1L),
                createBooleansBlock(true, false));
    }

    @Test
    public void testDuplicateKeysValues()
            throws Exception
    {
        MapType mapType = mapType(VARCHAR, BIGINT);
        InternalAggregationFunction aggregationFunction = metadata.getFunctionRegistry().getAggregateFunctionImplementation(
                new Signature(NAME,
                        AGGREGATE,
                        mapType.getTypeSignature(),
                        parseTypeSignature(StandardTypes.VARCHAR)));
        assertAggregation(
                aggregationFunction,
                ImmutableMap.of("a", 2L, "b", 1L),
                createStringsBlock("a", "b", "a"));

        mapType = mapType(TIMESTAMP_WITH_TIME_ZONE, BIGINT);
        aggregationFunction = metadata.getFunctionRegistry().getAggregateFunctionImplementation(
                new Signature(NAME,
                        AGGREGATE,
                        mapType.getTypeSignature(),
                        parseTypeSignature(StandardTypes.TIMESTAMP_WITH_TIME_ZONE)));
        long timestampWithTimeZone1 = packDateTimeWithZone(new DateTime(1970, 1, 1, 0, 0, 0, 0, DATE_TIME_ZONE).getMillis(), TIME_ZONE_KEY);
        long timestampWithTimeZone2 = packDateTimeWithZone(new DateTime(2015, 1, 1, 0, 0, 0, 0, DATE_TIME_ZONE).getMillis(), TIME_ZONE_KEY);
        assertAggregation(
                aggregationFunction,
                ImmutableMap.of(new SqlTimestampWithTimeZone(timestampWithTimeZone1), 2L, new SqlTimestampWithTimeZone(timestampWithTimeZone2), 1L),
                createLongsBlock(timestampWithTimeZone1, timestampWithTimeZone1, timestampWithTimeZone2));
    }

    @Test
    public void testWithNulls()
            throws Exception
    {
        MapType mapType = mapType(BIGINT, BIGINT);
        InternalAggregationFunction aggregationFunction = metadata.getFunctionRegistry().getAggregateFunctionImplementation(
                new Signature(NAME,
                        AGGREGATE,
                        mapType.getTypeSignature(),
                        parseTypeSignature(StandardTypes.BIGINT)));
        assertAggregation(
                aggregationFunction,
                ImmutableMap.of(1L, 1L, 2L, 1L),
                createLongsBlock(2L, null, 1L));

        mapType = mapType(BIGINT, BIGINT);
        aggregationFunction = metadata.getFunctionRegistry().getAggregateFunctionImplementation(
                new Signature(NAME,
                        AGGREGATE,
                        mapType.getTypeSignature(),
                        parseTypeSignature(StandardTypes.BIGINT)));
        assertAggregation(
                aggregationFunction,
                null,
                createLongsBlock((Long) null));
    }

    @Test
    public void testArrayHistograms()
            throws Exception
    {
        ArrayType arrayType = new ArrayType(VARCHAR);
        MapType mapType = mapType(arrayType, BIGINT);
        InternalAggregationFunction aggregationFunction = metadata.getFunctionRegistry().getAggregateFunctionImplementation(
                new Signature(NAME,
                        AGGREGATE,
                        mapType.getTypeSignature(),
                        arrayType.getTypeSignature()));

        assertAggregation(
                aggregationFunction,
                ImmutableMap.of(ImmutableList.of("a", "b", "c"), 1L, ImmutableList.of("d", "e", "f"), 1L, ImmutableList.of("c", "b", "a"), 1L),
                createStringArraysBlock(ImmutableList.of(ImmutableList.of("a", "b", "c"), ImmutableList.of("d", "e", "f"), ImmutableList.of("c", "b", "a"))));
    }

    @Test
    public void testMapHistograms()
            throws Exception
    {
        MapType innerMapType = mapType(VARCHAR, VARCHAR);
        MapType mapType = mapType(innerMapType, BIGINT);
        InternalAggregationFunction aggregationFunction = metadata.getFunctionRegistry().getAggregateFunctionImplementation(
                new Signature(NAME,
                        AGGREGATE,
                        mapType.getTypeSignature(),
                        innerMapType.getTypeSignature()));

        BlockBuilder builder = innerMapType.createBlockBuilder(new BlockBuilderStatus(), 3);
        innerMapType.writeObject(builder, mapBlockOf(VARCHAR, VARCHAR, ImmutableMap.of("a", "b")));
        innerMapType.writeObject(builder, mapBlockOf(VARCHAR, VARCHAR, ImmutableMap.of("c", "d")));
        innerMapType.writeObject(builder, mapBlockOf(VARCHAR, VARCHAR, ImmutableMap.of("e", "f")));

        assertAggregation(
                aggregationFunction,
                ImmutableMap.of(ImmutableMap.of("a", "b"), 1L, ImmutableMap.of("c", "d"), 1L, ImmutableMap.of("e", "f"), 1L),
                builder.build());
    }

    @Test
    public void testRowHistograms()
            throws Exception
    {
        RowType innerRowType = new RowType(ImmutableList.of(BIGINT, DOUBLE), Optional.of(ImmutableList.of("f1", "f2")));
        MapType mapType = mapType(innerRowType, BIGINT);
        InternalAggregationFunction aggregationFunction = metadata.getFunctionRegistry().getAggregateFunctionImplementation(
                new Signature(NAME,
                        AGGREGATE,
                        mapType.getTypeSignature(),
                        innerRowType.getTypeSignature()));

        BlockBuilder builder = innerRowType.createBlockBuilder(new BlockBuilderStatus(), 3);
        innerRowType.writeObject(builder, toRow(ImmutableList.of(BIGINT, DOUBLE), 1L, 1.0));
        innerRowType.writeObject(builder, toRow(ImmutableList.of(BIGINT, DOUBLE), 2L, 2.0));
        innerRowType.writeObject(builder, toRow(ImmutableList.of(BIGINT, DOUBLE), 3L, 3.0));

        assertAggregation(
                aggregationFunction,
                ImmutableMap.of(ImmutableList.of(1L, 1.0), 1L, ImmutableList.of(2L, 2.0), 1L, ImmutableList.of(3L, 3.0), 1L),
                builder.build());
    }

    @Test
    public void testLargerHistograms()
            throws Exception
    {
        MapType mapType = mapType(VARCHAR, BIGINT);
        InternalAggregationFunction aggregationFunction = metadata.getFunctionRegistry().getAggregateFunctionImplementation(
                new Signature(NAME,
                        AGGREGATE,
                        mapType.getTypeSignature(),
                        parseTypeSignature(StandardTypes.VARCHAR)));
        assertAggregation(
                aggregationFunction,
                ImmutableMap.of("a", 25L, "b", 10L, "c", 12L, "d", 1L, "e", 2L),
                createStringsBlock("a", "b", "c", "d", "e", "e", "c", "a", "a", "a", "b", "a", "a", "a", "a", "b", "a", "a", "a", "a", "b", "a", "a", "a", "a", "b", "a", "a", "a", "a", "b", "a", "c", "c", "b", "a", "c", "c", "b", "a", "c", "c", "b", "a", "c", "c", "b", "a", "c", "c"));
    }
}
