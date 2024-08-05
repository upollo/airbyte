package io.airbyte.integrations.source.bigquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.api.gax.rpc.ServerStream;
import com.google.cloud.bigquery.storage.v1.BigQueryReadClient;
import com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest;
import com.google.cloud.bigquery.storage.v1.DataFormat;
import com.google.cloud.bigquery.storage.v1.ReadSession;
import com.google.cloud.bigquery.storage.v1.ReadRowsRequest;
import com.google.cloud.bigquery.storage.v1.ReadRowsResponse;
import com.google.common.base.Preconditions;
import com.google.protobuf.Timestamp;
import io.airbyte.commons.util.AutoCloseableIterator;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/* Iterator to read from BigQueryStorage. Not thread-safe. */
class BigQueryStorageIterator implements AutoCloseableIterator<JsonNode> {

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
        ServerStream<ReadRowsResponse> stream = client.readRowsCallable().call(
            ReadRowsRequest.newBuilder()
                .setReadStream(session.getStreams(0).getName())
                .build()
        );
        return new BigQueryStorageIterator(
            new ArrowParser(session.getArrowSchema(), ArrowAllocatorSingleton.getInstance()),
            stream
        );
    }

    private final ArrowParser parser;
    private final ServerStream<ReadRowsResponse> stream;

    private Iterator<JsonNode> currentBatch;
    private boolean isClosed = false;

    /** Iterator which uses the given parser to produce JsonNodes from reading the given stream. */
    private BigQueryStorageIterator(
        ArrowParser parser,
        ServerStream<ReadRowsResponse> stream
    ) throws IOException {
        this.parser = parser;
        this.stream = stream;

        this.currentBatch = loadNextBatch();
    }

    /** Loads and returns the next batch of records, or none remain. */
    private Iterator<JsonNode> loadNextBatch() throws IOException {
        Iterator<ReadRowsResponse> it = stream.iterator();
        if (it.hasNext()) {
            return parser.processRows(it.next().getArrowRecordBatch());
        } else {
            return null;
        }
    }

    @Override public boolean hasNext() {
        if (currentBatch == null) {
            return false;
        }
        if (!currentBatch.hasNext()) {
            try {
                currentBatch = loadNextBatch();
            } catch (IOException ioe) {
                throw new RuntimeException("Error processing rows", ioe);
            }
        }
        return currentBatch != null && currentBatch.hasNext();
    }

    @Override public JsonNode next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return currentBatch.next();
    }

    @Override public void close() {
        if (!isClosed) {
            parser.close();
            stream.cancel();
            isClosed = true;
            currentBatch = null;
        }
    }
}
