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
package com.facebook.presto.connector.informationSchema;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.InternalTable;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.OperatorNotFoundException;
import com.facebook.presto.metadata.QualifiedObjectName;
import com.facebook.presto.metadata.QualifiedTablePrefix;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.metadata.TableHandle;
import com.facebook.presto.metadata.TableLayout;
import com.facebook.presto.metadata.TableLayoutResult;
import com.facebook.presto.metadata.ViewDefinition;
import com.facebook.presto.security.AccessControl;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorPageSource;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorSplit;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.FixedPageSource;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.TableNotFoundException;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.connector.ConnectorPageSourceProvider;
import com.facebook.presto.spi.connector.ConnectorTransactionHandle;
import com.facebook.presto.spi.predicate.NullableValue;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.security.GrantInfo;
import com.facebook.presto.spi.security.PrivilegeInfo;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import io.airlift.slice.Slice;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.connector.informationSchema.InformationSchemaMetadata.TABLE_COLUMNS;
import static com.facebook.presto.connector.informationSchema.InformationSchemaMetadata.TABLE_INTERNAL_PARTITIONS;
import static com.facebook.presto.connector.informationSchema.InformationSchemaMetadata.TABLE_SCHEMATA;
import static com.facebook.presto.connector.informationSchema.InformationSchemaMetadata.TABLE_TABLES;
import static com.facebook.presto.connector.informationSchema.InformationSchemaMetadata.TABLE_TABLE_PRIVILEGES;
import static com.facebook.presto.connector.informationSchema.InformationSchemaMetadata.TABLE_VIEWS;
import static com.facebook.presto.connector.informationSchema.InformationSchemaMetadata.informationSchemaTableColumns;
import static com.facebook.presto.metadata.MetadataListing.listSchemas;
import static com.facebook.presto.metadata.MetadataListing.listTableColumns;
import static com.facebook.presto.metadata.MetadataListing.listTablePrivileges;
import static com.facebook.presto.metadata.MetadataListing.listTables;
import static com.facebook.presto.metadata.MetadataListing.listViews;
import static com.facebook.presto.spi.type.VarcharType.createUnboundedVarcharType;
import static com.facebook.presto.spi.type.Varchars.isVarcharType;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Sets.union;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public class InformationSchemaPageSourceProvider
        implements ConnectorPageSourceProvider
{
    private final Metadata metadata;
    private final AccessControl accessControl;

    public InformationSchemaPageSourceProvider(Metadata metadata, AccessControl accessControl)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
    }

    @Override
    public ConnectorPageSource createPageSource(ConnectorTransactionHandle transactionHandle, ConnectorSession session, ConnectorSplit split, List<ColumnHandle> columns)
    {
        InternalTable table = getInternalTable(transactionHandle, session, split, columns);

        List<Integer> channels = new ArrayList<>();
        for (ColumnHandle column : columns) {
            String columnName = ((InformationSchemaColumnHandle) column).getColumnName();
            int columnIndex = table.getColumnIndex(columnName);
            channels.add(columnIndex);
        }

        ImmutableList.Builder<Page> pages = ImmutableList.builder();
        for (Page page : table.getPages()) {
            Block[] blocks = new Block[channels.size()];
            for (int index = 0; index < blocks.length; index++) {
                blocks[index] = page.getBlock(channels.get(index));
            }
            pages.add(new Page(page.getPositionCount(), blocks));
        }
        return new FixedPageSource(pages.build());
    }

    private InternalTable getInternalTable(ConnectorTransactionHandle transactionHandle, ConnectorSession connectorSession, ConnectorSplit connectorSplit, List<ColumnHandle> columns)
    {
        InformationSchemaTransactionHandle transaction = (InformationSchemaTransactionHandle) transactionHandle;
        InformationSchemaSplit split = (InformationSchemaSplit) connectorSplit;

        requireNonNull(columns, "columns is null");

        InformationSchemaTableHandle handle = split.getTableHandle();
        Map<String, NullableValue> filters = split.getFilters();

        Session session = Session.builder(metadata.getSessionPropertyManager())
                .setTransactionId(transaction.getTransactionId())
                .setQueryId(new QueryId(connectorSession.getQueryId()))
                .setIdentity(connectorSession.getIdentity())
                .setSource("information_schema")
                .setCatalog("") // default catalog is not be used
                .setSchema("") // default schema is not be used
                .setTimeZoneKey(connectorSession.getTimeZoneKey())
                .setLocale(connectorSession.getLocale())
                .setStartTime(connectorSession.getStartTime())
                .build();

        return getInformationSchemaTable(session, handle.getCatalogName(), handle.getSchemaTableName(), filters);
    }

    public InternalTable getInformationSchemaTable(Session session, String catalog, SchemaTableName table, Map<String, NullableValue> filters)
    {
        if (table.equals(TABLE_COLUMNS)) {
            return buildColumns(session, catalog, filters);
        }
        if (table.equals(TABLE_TABLES)) {
            return buildTables(session, catalog, filters);
        }
        if (table.equals(TABLE_VIEWS)) {
            return buildViews(session, catalog, filters);
        }
        if (table.equals(TABLE_SCHEMATA)) {
            return buildSchemata(session, catalog);
        }
        if (table.equals(TABLE_INTERNAL_PARTITIONS)) {
            return buildPartitions(session, catalog, filters);
        }
        if (table.equals(TABLE_TABLE_PRIVILEGES)) {
            return buildTablePrivileges(session, catalog, filters);
        }

        throw new IllegalArgumentException(format("table does not exist: %s", table));
    }

    private InternalTable buildColumns(Session session, String catalogName, Map<String, NullableValue> filters)
    {
        InternalTable.Builder table = InternalTable.builder(informationSchemaTableColumns(TABLE_COLUMNS));
        QualifiedTablePrefix prefix = extractQualifiedTablePrefix(catalogName, filters);
        for (Entry<SchemaTableName, List<ColumnMetadata>> entry : listTableColumns(session, metadata, accessControl, prefix).entrySet()) {
            SchemaTableName tableName = entry.getKey();
            int ordinalPosition = 1;
            for (ColumnMetadata column : entry.getValue()) {
                if (column.isHidden()) {
                    continue;
                }
                table.add(
                        catalogName,
                        tableName.getSchemaName(),
                        tableName.getTableName(),
                        column.getName(),
                        ordinalPosition,
                        null,
                        "YES",
                        column.getType().getDisplayName(),
                        column.getComment(),
                        column.getExtraInfo());
                ordinalPosition++;
            }
        }
        return table.build();
    }

    private InternalTable buildTables(Session session, String catalogName, Map<String, NullableValue> filters)
    {
        QualifiedTablePrefix prefix = extractQualifiedTablePrefix(catalogName, filters);
        Set<SchemaTableName> tables = listTables(session, metadata, accessControl, prefix);
        Set<SchemaTableName> views = listViews(session, metadata, accessControl, prefix);

        InternalTable.Builder table = InternalTable.builder(informationSchemaTableColumns(TABLE_TABLES));
        for (SchemaTableName name : union(tables, views)) {
            // if table and view names overlap, the view wins
            String type = views.contains(name) ? "VIEW" : "BASE TABLE";
            table.add(
                    catalogName,
                    name.getSchemaName(),
                    name.getTableName(),
                    type);
        }
        return table.build();
    }

    private InternalTable buildTablePrivileges(Session session, String catalogName, Map<String, NullableValue> filters)
    {
        QualifiedTablePrefix prefix = extractQualifiedTablePrefix(catalogName, filters);
        List<GrantInfo> grants = ImmutableList.copyOf(listTablePrivileges(session, metadata, accessControl, prefix));
        InternalTable.Builder table = InternalTable.builder(informationSchemaTableColumns(TABLE_TABLE_PRIVILEGES));
        for (GrantInfo grant : grants) {
            for (PrivilegeInfo privilegeInfo : grant.getPrivilegeInfo()) {
                table.add(
                        grant.getGrantor().orElse(null),
                        grant.getIdentity().getUser(),
                        catalogName,
                        grant.getSchemaTableName().getSchemaName(),
                        grant.getSchemaTableName().getTableName(),
                        privilegeInfo.getPrivilege().name(),
                        privilegeInfo.isGrantOption(),
                        grant.getWithHierarchy().orElse(null));
            }
        }
        return table.build();
    }

    private InternalTable buildViews(Session session, String catalogName, Map<String, NullableValue> filters)
    {
        InternalTable.Builder table = InternalTable.builder(informationSchemaTableColumns(TABLE_VIEWS));
        for (Entry<QualifiedObjectName, ViewDefinition> entry : getViews(session, catalogName, filters).entrySet()) {
            table.add(
                    entry.getKey().getCatalogName(),
                    entry.getKey().getSchemaName(),
                    entry.getKey().getObjectName(),
                    entry.getValue().getOriginalSql());
        }
        return table.build();
    }

    private Map<QualifiedObjectName, ViewDefinition> getViews(Session session, String catalogName, Map<String, NullableValue> filters)
    {
        return metadata.getViews(session, extractQualifiedTablePrefix(catalogName, filters));
    }

    private InternalTable buildSchemata(Session session, String catalogName)
    {
        InternalTable.Builder table = InternalTable.builder(informationSchemaTableColumns(TABLE_SCHEMATA));
        for (String schema : listSchemas(session, metadata, accessControl, catalogName)) {
            table.add(catalogName, schema);
        }
        return table.build();
    }

    private InternalTable buildPartitions(Session session, String catalogName, Map<String, NullableValue> filters)
    {
        QualifiedObjectName tableName = extractQualifiedTableName(catalogName, filters);

        InternalTable.Builder table = InternalTable.builder(informationSchemaTableColumns(TABLE_INTERNAL_PARTITIONS));

        Optional<TableHandle> tableHandle = metadata.getTableHandle(session, tableName);
        if (!tableHandle.isPresent()) {
            throw new TableNotFoundException(tableName.asSchemaTableName());
        }

        List<TableLayoutResult> layouts = metadata.getLayouts(session, tableHandle.get(), Constraint.alwaysTrue(), Optional.empty());

        if (layouts.size() == 1) {
            Map<ColumnHandle, String> columnHandles = ImmutableBiMap.copyOf(metadata.getColumnHandles(session, tableHandle.get())).inverse();
            Map<ColumnHandle, MethodHandle> methodHandles = new HashMap<>();
            for (ColumnHandle columnHandle : columnHandles.keySet()) {
                try {
                    ColumnMetadata columnMetadata = metadata.getColumnMetadata(session, tableHandle.get(), columnHandle);
                    Signature operator = metadata.getFunctionRegistry().getCoercion(columnMetadata.getType(), createUnboundedVarcharType());
                    MethodHandle methodHandle = metadata.getFunctionRegistry().getScalarFunctionImplementation(operator).getMethodHandle();
                    methodHandles.put(columnHandle, methodHandle);
                }
                catch (OperatorNotFoundException exception) {
                    // Do not put the columnHandle in the map.
                }
            }

            TableLayout layout = Iterables.getOnlyElement(layouts).getLayout();
            layout.getDiscretePredicates().ifPresent(predicates -> {
                int partitionNumber = 1;
                for (TupleDomain<ColumnHandle> domain : predicates.getPredicates()) {
                    for (Entry<ColumnHandle, NullableValue> entry : TupleDomain.extractFixedValues(domain).get().entrySet()) {
                        ColumnHandle columnHandle = entry.getKey();
                        String columnName = columnHandles.get(columnHandle);
                        String value = null;
                        if (entry.getValue().getValue() != null) {
                            if (methodHandles.containsKey(columnHandle)) {
                                try {
                                    value = ((Slice) methodHandles.get(columnHandle).invokeWithArguments(entry.getValue().getValue())).toStringUtf8();
                                }
                                catch (Throwable throwable) {
                                    throw Throwables.propagate(throwable);
                                }
                            }
                            else {
                                // OperatorNotFoundException was thrown for this columnHandle
                                value = "<UNREPRESENTABLE VALUE>";
                            }
                        }
                        table.add(
                                catalogName,
                                tableName.getSchemaName(),
                                tableName.getObjectName(),
                                partitionNumber,
                                columnName,
                                value);
                    }
                    partitionNumber++;
                }
            });
        }
        return table.build();
    }

    private static QualifiedObjectName extractQualifiedTableName(String catalogName, Map<String, NullableValue> filters)
    {
        Optional<String> schemaName = getFilterColumn(filters, "table_schema");
        checkArgument(schemaName.isPresent(), "filter is required for column: %s.%s", TABLE_INTERNAL_PARTITIONS, "table_schema");
        Optional<String> tableName = getFilterColumn(filters, "table_name");
        checkArgument(tableName.isPresent(), "filter is required for column: %s.%s", TABLE_INTERNAL_PARTITIONS, "table_name");
        return new QualifiedObjectName(catalogName, schemaName.get(), tableName.get());
    }

    private static QualifiedTablePrefix extractQualifiedTablePrefix(String catalogName, Map<String, NullableValue> filters)
    {
        Optional<String> schemaName = getFilterColumn(filters, "table_schema");
        Optional<String> tableName = getFilterColumn(filters, "table_name");
        if (!schemaName.isPresent()) {
            return new QualifiedTablePrefix(catalogName, Optional.empty(), Optional.empty());
        }
        return new QualifiedTablePrefix(catalogName, schemaName, tableName);
    }

    private static Optional<String> getFilterColumn(Map<String, NullableValue> filters, String columnName)
    {
        NullableValue value = filters.get(columnName);
        if (value == null || value.getValue() == null) {
            return Optional.empty();
        }
        if (isVarcharType(value.getType())) {
            return Optional.of(((Slice) value.getValue()).toStringUtf8().toLowerCase(ENGLISH));
        }
        return Optional.empty();
    }
}
