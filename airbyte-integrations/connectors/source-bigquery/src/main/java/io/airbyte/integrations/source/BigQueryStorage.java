import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.bigquery.storage.v1.*;
import com.google.protobuf.Timestamp;

import java.io.IOException;
import java.util.List;

public class StorageQuery {

    /**
     * Executes a storage query and returns an iterator over the results.
     *
     * @param client         The BigQueryReadClient to use for the query.
     * @param tableName      The fully qualified table name.
     * @param filter         The row restriction filter.
     * @param columnNames    The list of column names to select.
     * @param snapshotMillis Optional snapshot time in milliseconds.
     * @return An AutoCloseableIterator over JsonNode objects representing the query results.
     * @throws IOException if there is an error during query execution.
     */
    public static AutoCloseableIterator<JsonNode> storageQuery(
            BigQueryReadClient client,
            String tableName,
            String filter,
            List<String> columnNames,
            Integer snapshotMillis
    ) throws IOException {

        // Construct the parent project ID from the table name
        String projectId = tableName.split("/")[1];
        String parent = String.format("projects/%s", projectId);

        // Build the TableReadOptions with the specified columns and filter
        TableReadOptions.Builder optionsBuilder = TableReadOptions.newBuilder()
                .setRowRestriction(filter);

        for (String column : columnNames) {
            optionsBuilder.addSelectedFields(column);
        }

        TableReadOptions options = optionsBuilder.build();

        // Create a ReadSession
        ReadSession.Builder sessionBuilder = ReadSession.newBuilder()
                .setTable(tableName)
                .setDataFormat(DataFormat.ARROW)
                .setReadOptions(options);

        // Optionally set the snapshot time
        if (snapshotMillis != null) {
            Timestamp t = Timestamp.newBuilder()
                    .setSeconds(snapshotMillis / 1000)
                    .setNanos((int) ((snapshotMillis % 1000) * 1000000))
                    .build();
            TableModifiers modifiers = TableModifiers.newBuilder().setSnapshotTime(t).build();
            sessionBuilder.setTableModifiers(modifiers);
        }

        CreateReadSessionRequest request = CreateReadSessionRequest.newBuilder()
                .setParent(parent)
                .setReadSession(sessionBuilder)
                .setMaxStreamCount(1)
                .build();

        ReadSession session = client.createReadSession(request);

        return new BigQueryIterator(client, session);
    }
}
