package io.airbyte.integrations.source.bigquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.bigquery.storage.v1.ArrowRecordBatch;
import com.google.cloud.bigquery.storage.v1.ArrowSchema;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;

class ArrowParser implements AutoCloseable {
  private final ArrowSchema schema;

  public ArrowParser(ArrowSchema schema) {
    this.schema = schema;
  }

  public Iterator<JsonNode> processRows(ArrowRecordBatch batch) throws IOException {
    return new ArrayList<JsonNode>().iterator();
  }

  @Override public void close() {}
}
