/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.source.bigquery;

import static io.airbyte.cdk.integrations.source.relationaldb.RelationalDbQueryUtils.enquoteIdentifierList;
import static io.airbyte.cdk.integrations.source.relationaldb.RelationalDbQueryUtils.getFullyQualifiedTableNameWithQuoting;
import static io.airbyte.cdk.integrations.source.relationaldb.RelationalDbQueryUtils.queryTable;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.Table;
import com.google.common.collect.ImmutableMap;
import io.airbyte.cdk.db.SqlDatabase;
import io.airbyte.cdk.db.bigquery.BigQuerySourceOperations;
import io.airbyte.cdk.integrations.base.IntegrationRunner;
import io.airbyte.cdk.integrations.base.Source;
import io.airbyte.cdk.integrations.source.relationaldb.AbstractDbSource;
import io.airbyte.cdk.integrations.source.relationaldb.CursorInfo;
import io.airbyte.cdk.integrations.source.relationaldb.RelationalDbQueryUtils;
import io.airbyte.cdk.integrations.source.relationaldb.TableInfo;
import io.airbyte.commons.functional.CheckedConsumer;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.stream.AirbyteStreamUtils;
import io.airbyte.commons.util.AutoCloseableIterator;
import io.airbyte.commons.util.AutoCloseableIterators;
import io.airbyte.protocol.models.AirbyteStreamNameNamespacePair;
import io.airbyte.protocol.models.CommonField;
import io.airbyte.protocol.models.JsonSchemaType;
import io.airbyte.protocol.models.v0.SyncMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BigQuerySource extends AbstractDbSource<StandardSQLTypeName, BigQueryStorageDatabase> implements Source {

  private static final Logger LOGGER = LoggerFactory.getLogger(BigQuerySource.class);
  private static final String QUOTE = "`";

  public static final String CONFIG_DATASET_ID = "dataset_id";
  public static final String CONFIG_PROJECT_ID = "project_id";
  public static final String CONFIG_CREDS = "credentials_json";
  public static final String CONFIG_TABLE_FILTERS = "table_filters";

  private JsonNode dbConfig;
  private final BigQuerySourceOperations sourceOperations = new BigQuerySourceOperations();

  protected BigQuerySource() {
    super(null);
  }

  @Override
  public JsonNode toDatabaseConfig(final JsonNode config) {
    final var conf = ImmutableMap.builder()
        .put(CONFIG_PROJECT_ID, config.get(CONFIG_PROJECT_ID).asText())
        .put(CONFIG_CREDS, config.get(CONFIG_CREDS).asText());
    if (config.hasNonNull(CONFIG_DATASET_ID)) {
      conf.put(CONFIG_DATASET_ID, config.get(CONFIG_DATASET_ID).asText());
    }
    if (config.hasNonNull(CONFIG_TABLE_FILTERS)) {
      conf.put(CONFIG_TABLE_FILTERS, config.get(CONFIG_TABLE_FILTERS));
    }
    return Jsons.jsonNode(conf.build());
  }

  @Override
  protected BigQueryStorageDatabase createDatabase(final JsonNode sourceConfig) {
    dbConfig = Jsons.clone(sourceConfig);
    final BigQueryStorageDatabase database = new BigQueryStorageDatabase(
      sourceConfig.get(CONFIG_PROJECT_ID).asText(),
      sourceConfig.get(CONFIG_CREDS).asText());
    database.setSourceConfig(sourceConfig);
    database.setDatabaseConfig(toDatabaseConfig(sourceConfig));
    return database;
  }

  @Override
  public List<CheckedConsumer<BigQueryStorageDatabase, Exception>> getCheckOperations(final JsonNode config) {
    final List<CheckedConsumer<BigQueryStorageDatabase, Exception>> checkList = new ArrayList<>();
    checkList.add(database -> {
      if (database.query("select 1").count() < 1)
        throw new Exception("Unable to execute any query on the source!");
      else
        LOGGER.info("The source passed the basic query test!");
    });

    checkList.add(database -> {
      if (isDatasetConfigured(database)) {
        database.query(String.format("select 1 from %s where 1=0",
            getFullyQualifiedTableNameWithQuoting(getConfigDatasetId(database), "INFORMATION_SCHEMA.TABLES", getQuoteString())));
        LOGGER.info("The source passed the Dataset query test!");
      } else {
        LOGGER.info("The Dataset query test is skipped due to not configured datasetId!");
      }
    });

    return checkList;
  }

  @Override
  protected JsonSchemaType getAirbyteType(final StandardSQLTypeName columnType) {
    return sourceOperations.getAirbyteType(columnType);
  }

  @Override
  public Set<String> getExcludedInternalNameSpaces() {
    return Collections.emptySet();
  }

  @Override
  protected List<TableInfo<CommonField<StandardSQLTypeName>>> discoverInternal(final BigQueryStorageDatabase database) throws Exception {
    return discoverInternal(database, null);
  }

  @Override
  protected List<TableInfo<CommonField<StandardSQLTypeName>>> discoverInternal(final BigQueryStorageDatabase database, final String schema) {
    final String projectId = dbConfig.get(CONFIG_PROJECT_ID).asText();
    final List<Table> tables =
        (isDatasetConfigured(database) ? database.getDatasetTables(getConfigDatasetId(database)) : database.getProjectTables(projectId));
    final List<TableInfo<CommonField<StandardSQLTypeName>>> result = new ArrayList<>();
    tables.stream().map(table -> TableInfo.<CommonField<StandardSQLTypeName>>builder()
        .nameSpace(table.getTableId().getDataset())
        .name(table.getTableId().getTable())
        .fields(Objects.requireNonNull(table.getDefinition().getSchema()).getFields().stream()
            .map(f -> {
              final StandardSQLTypeName standardType;
              if (f.getType().getStandardType() == StandardSQLTypeName.STRUCT && f.getMode() == Field.Mode.REPEATED) {
                standardType = StandardSQLTypeName.ARRAY;
              } else
                standardType = f.getType().getStandardType();

              return new CommonField<>(f.getName(), standardType);
            })
            .collect(Collectors.toList()))
        .build())
        .forEach(result::add);
    return result;
  }

  @Override
  protected Map<String, List<String>> discoverPrimaryKeys(final BigQueryStorageDatabase database,
                                                          final List<TableInfo<CommonField<StandardSQLTypeName>>> tableInfos) {
    return Collections.emptyMap();
  }

  @Override
  protected String getQuoteString() {
    return QUOTE;
  }

  private static String toCursorLiteral(StandardSQLTypeName type, String value) {
    // Most cursor types are currently unsupported, given:
    // a) We aim to generate SQL injection-proof literals and that is a dangerous task.
    // b) We would need to test more examples to know the exact format expect values in.
    switch (type) {
      case StandardSQLTypeName.INT64:
        return String.format("%d", Long.parseLong(value));
      case StandardSQLTypeName.TIMESTAMP:
        return String.format("timestamp_micros(%d)", Long.parseLong(value));
      default:
        throw new RuntimeException("Unsupported Bigquery Storage cursor type: " + type + " value: " + value);
    }
  }

  @Override
  public AutoCloseableIterator<JsonNode> queryTableIncremental(final BigQueryStorageDatabase database,
                                                               final List<String> columnNames,
                                                               final String schemaName,
                                                               final String tableName,
                                                               final CursorInfo cursorInfo,
                                                               final StandardSQLTypeName cursorFieldType) {
    // TODO: Handle more filter types.
    // sourceOperations.getQueryParameter(cursorFieldType, cursorInfo.getCursor());
    LOGGER.info("Incremental from cursor type: {} value: {}", cursorFieldType, cursorInfo.getCursor());
    String cursorLiteral = toCursorLiteral(cursorFieldType, cursorInfo.getCursor());
    String filter = String.format("%s > %s", cursorInfo.getCursorField(), cursorLiteral);
    LOGGER.info("Incremental with filter: {}", filter);
    return queryStorageApi(database, schemaName, tableName, filter, columnNames);
  }

  @Override
  protected AutoCloseableIterator<JsonNode> queryTableFullRefresh(final BigQueryStorageDatabase database,
                                                                  final List<String> columnNames,
                                                                  final String schemaName,
                                                                  final String tableName,
                                                                  final SyncMode syncMode,
                                                                  final Optional<String> cursorField) {
    LOGGER.info("Queueing query for table: {}", tableName);
    return queryStorageApi(database, schemaName, tableName, "", columnNames);
  }

  @Override
  public boolean isCursorType(final StandardSQLTypeName standardSQLTypeName) {
    return true;
  }

  private AutoCloseableIterator<JsonNode> queryStorageApi(final BigQueryStorageDatabase database,
                                                          final String schemaName,
                                                          final String tableName,
                                                          final String queryFilter,
                                                          final List<String> columnNames) {
    final String filter = prepareFilter(schemaName, tableName, queryFilter);
    if (!filter.isEmpty()) {
      LOGGER.info("Final filter for query of {}.{}: {}", schemaName, tableName, filter);
    }

    String tableSpec = String.format(
        "projects/%s/datasets/%s/tables/%s",
        dbConfig.get(CONFIG_PROJECT_ID).asText(),
        schemaName,
        tableName
    );

    final AirbyteStreamNameNamespacePair airbyteStream = AirbyteStreamUtils.convertFromNameAndNamespace(tableName, schemaName);
    return AutoCloseableIterators.lazyIterator(() -> {
      try {
        return BigQueryStorageIterator.create(
            database.makeStorageReadClient(),
            tableSpec,
            filter,
            columnNames,
            -1
        );
      } catch (final Exception e) {
        throw new RuntimeException(tableSpec, e);
      }
    }, airbyteStream);
  }

  private boolean isDatasetConfigured(final SqlDatabase database) {
    final JsonNode config = database.getSourceConfig();
    return config.hasNonNull(CONFIG_DATASET_ID) ? !config.get(CONFIG_DATASET_ID).asText().isEmpty() : false;
  }

  private String getConfigDatasetId(final SqlDatabase database) {
    return (isDatasetConfigured(database) ? database.getSourceConfig().get(CONFIG_DATASET_ID).asText() : "");
  }

  private String prepareFilter(String schemaName, String tableName, String queryFilter) {
    final String tableFilter = parseTableFilters(dbConfig.get(CONFIG_TABLE_FILTERS))
        .get(String.format("%s.%s", schemaName, tableName));
    if (tableFilter == null || tableFilter.isEmpty()) {
      return queryFilter;
    }
    LOGGER.info("Applying table filter to query: {}", tableFilter);
    if (queryFilter.isEmpty()) {
      return tableFilter;
    }
    return String.format("(%s) AND (%s)", tableFilter, queryFilter);
  }

  static Map<String, String> parseTableFilters(JsonNode tableFilters) {
    if (tableFilters == null) {
      return ImmutableMap.<String, String>of();
    }
    final ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    for (JsonNode n : tableFilters) {
      var split = n.asText().split("=");
      builder.put(split[0], split[1]);
    }
    return builder.build();
  }

  public static void main(final String[] args) throws Exception {
    final Source source = new BigQuerySource();
    LOGGER.info("starting source: {}", BigQuerySource.class);
    new IntegrationRunner(source).run(args);
    LOGGER.info("completed source: {}", BigQuerySource.class);
  }

  @Override
  public void close() throws Exception {}

}
