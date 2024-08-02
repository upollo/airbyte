import com.fasterxml.jackson.databind.JsonNode;
import com.google.api.gax.rpc.ServerStream;
import com.google.cloud.bigquery.storage.v1.BigQueryReadClient;
import com.google.cloud.bigquery.storage.v1.ReadRowsRequest;
import com.google.cloud.bigquery.storage.v1.ReadRowsResponse;
import com.google.cloud.bigquery.storage.v1.ReadSession;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class BigQueryIterator implements AutoCloseableIterator<JsonNode> {

    private final ServerStream<ReadRowsResponse> stream;
    private final SimpleRowReader reader;
    private Iterator<JsonNode> currentBatch;
    private boolean isClosed = false;

    public BigQueryIterator(BigQueryReadClient client, ReadSession session) throws IOException {
        Preconditions.checkState(session.getStreamsCount() > 0);

        String streamName = session.getStreams(0).getName();
        ReadRowsRequest readRowsRequest = ReadRowsRequest.newBuilder().setReadStream(streamName).build();

        stream = client.readRowsCallable().call(readRowsRequest);
        reader = new SimpleRowReader(session.getArrowSchema());
        currentBatch = loadNextBatch();
    }

    private Iterator<JsonNode> loadNextBatch() {
        for (ReadRowsResponse response : stream) {
            Preconditions.checkState(response.hasArrowRecordBatch());
            try {
                List<JsonNode> rows = reader.processRows(response.getArrowRecordBatch());
                return rows.iterator();
            } catch (IOException e) {
                throw new RuntimeException("Error processing rows", e);
            }
        }
        return null;
    }

    @Override
    public boolean hasNext() {
        if (currentBatch == null) {
            return false;
        }
        if (!currentBatch.hasNext()) {
            currentBatch = loadNextBatch();
        }
        return currentBatch != null && currentBatch.hasNext();
    }

    @Override
    public JsonNode next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return currentBatch.next();
    }

    @Override
    public void close() {
        if (!isClosed) {
            reader.close();
            isClosed = true;
        }
    }
}
