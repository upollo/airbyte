package io.airbyte.integrations.source.bigquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.bigquery.storage.v1.BigQueryReadClient;
import com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest;
import com.google.cloud.bigquery.storage.v1.DataFormat;
import com.google.cloud.bigquery.storage.v1.ReadSession;
import com.google.protobuf.Timestamp;
import io.airbyte.commons.util.AutoCloseableIterator;
import java.io.IOException;
import java.util.List;

public class BigQueryStorageIterator implements AutoCloseableIterator<JsonNode> {

    /**
     * Executes a storage query and returns an iterator over the results.
     *
     * @param client         The BigQueryReadClient to use for the query.
     * @param tableName      The fully qualified table name.
     * @param filter         The row restriction filter.
     * @param columnNames    The column names to select.
     * @param snapshotMillis Optional snapshot time in milliseconds, or -1.
     * @return An AutoCloseableIterator over JsonNode objects representing the query results.
     * @throws IOException if there is an error during query execution.
     */
    public static AutoCloseableIterator<JsonNode> create(
        BigQueryReadClient client,
        String tableName,
        String filter,
        Iterable<String> columnNames,
        int snapshotMs
    ) throws IOException {
        String projectId = tableName.split("/")[1];

        CreateReadSessionRequest.Builder req = CreateReadSessionRequest.newBuilder()
            .setParent(String.format("projects/%s", projectId))
            .setMaxStreamCount(1);
        req.getReadSessionBuilder()
            .setTable(tableName)
            .setDataFormat(DataFormat.ARROW);
        req.getReadSessionBuilder().getReadOptionsBuilder()
            .setRowRestriction(filter)
            .addAllSelectedFields(columnNames);
        if (snapshotMs != -1) {
            req.getReadSessionBuilder().getTableModifiersBuilder().getSnapshotTimeBuilder()
                .setSeconds(snapshotMs / 1000)
                .setNanos((snapshotMs % 1000) * 1000000);
        }

        ReadSession session = client.createReadSession(req.build());
        return new BigQueryStorageIterator(client, session);
    }

    private final BigQueryReadClient client;
    private final ReadSession session;

    public BigQueryStorageIterator(
        BigQueryReadClient client,
        ReadSession session
    ) {
        this.client = client;
        this.session = session;
    }

    @Override public boolean hasNext() {
        // TODO: implement
        return false;
    }

    @Override public JsonNode next() {
        // TODO: implement
        return null;
    }

    @Override public void close() {
        // TODO: implement
    }
}
