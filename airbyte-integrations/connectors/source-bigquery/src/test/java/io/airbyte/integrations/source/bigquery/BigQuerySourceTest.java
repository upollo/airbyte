/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.source.bigquery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.resources.MoreResources;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BigQuerySourceTest {

  @Test
  public void testEmptyDatasetIdInConfig() throws IOException {
    final JsonNode configJson = Jsons.deserialize(MoreResources.readResource("test_config_empty_datasetid.json"));
    final JsonNode dbConfig = new BigQuerySource().toDatabaseConfig(configJson);
    assertTrue(dbConfig.get(BigQuerySource.CONFIG_DATASET_ID).isEmpty());
  }

  @Test
  public void testMissingDatasetIdInConfig() throws IOException {
    final JsonNode configJson = Jsons.deserialize(MoreResources.readResource("test_config_missing_datasetid.json"));
    final JsonNode dbConfig = new BigQuerySource().toDatabaseConfig(configJson);
    assertFalse(dbConfig.hasNonNull(BigQuerySource.CONFIG_DATASET_ID));
  }

  @Test
  public void testNullDatasetIdInConfig() throws IOException {
    final JsonNode configJson = Jsons.deserialize(MoreResources.readResource("test_config_null_datasetid.json"));
    final JsonNode dbConfig = new BigQuerySource().toDatabaseConfig(configJson);
    assertFalse(dbConfig.hasNonNull(BigQuerySource.CONFIG_DATASET_ID));
  }

  @Test
  public void testConfig() throws IOException {
    final JsonNode configJson = Jsons.deserialize(MoreResources.readResource("test_config.json"));
    final JsonNode dbConfig = new BigQuerySource().toDatabaseConfig(configJson);
    assertEquals("dataset", dbConfig.get(BigQuerySource.CONFIG_DATASET_ID).asText());
    assertEquals("project", dbConfig.get(BigQuerySource.CONFIG_PROJECT_ID).asText());
    assertEquals("credentials", dbConfig.get(BigQuerySource.CONFIG_CREDS).asText());
    assertEquals(ImmutableMap.<String, String>of(),
        BigQuerySource.parseTableFilters(dbConfig.get(BigQuerySource.CONFIG_TABLE_FILTERS)));
  }

  @Test
  public void testEmptyFiltersInConfig() throws IOException {
    final JsonNode configJson = Jsons.deserialize(MoreResources.readResource("test_config_empty_filters.json"));
    final JsonNode dbConfig = new BigQuerySource().toDatabaseConfig(configJson);
    assertEquals(ImmutableMap.<String, String>of(),
        BigQuerySource.parseTableFilters(dbConfig.get(BigQuerySource.CONFIG_TABLE_FILTERS)));
  }

  @Test
  public void testFiltersInConfig() throws IOException {
    final JsonNode configJson = Jsons.deserialize(MoreResources.readResource("test_config_filters.json"));
    final JsonNode dbConfig = new BigQuerySource().toDatabaseConfig(configJson);

    Map<String, String> expectedFilters = ImmutableMap.of(
        "prod.table_one", "last_updated > \"2024-01-01\"",
        "dev.table_one", "last_updated > \"2024-07-01\"");
    assertEquals(expectedFilters,
        BigQuerySource.parseTableFilters(dbConfig.get(BigQuerySource.CONFIG_TABLE_FILTERS)));
  }

}
