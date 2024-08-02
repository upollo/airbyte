import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.storage.v1.ArrowRecordBatch;
import com.google.cloud.bigquery.storage.v1.ArrowSchema;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorLoader;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ReadChannel;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SimpleRowReader implements AutoCloseable {

    private final BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
    private final VectorSchemaRoot root;
    private final VectorLoader loader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SimpleRowReader(ArrowSchema arrowSchema) throws IOException {
        Schema schema = MessageSerializer.deserializeSchema(
                new ReadChannel(
                        new ByteArrayReadableSeekableByteChannel(
                                arrowSchema.getSerializedSchema().toByteArray())));
        Preconditions.checkNotNull(schema);
        List<FieldVector> vectors = new ArrayList<>();
        for (Field field : schema.getFields()) {
            vectors.add(field.createVector(allocator));
        }
        root = new VectorSchemaRoot(vectors);
        loader = new VectorLoader(root);
    }

    public List<JsonNode> processRows(ArrowRecordBatch batch) throws IOException {
        org.apache.arrow.vector.ipc.message.ArrowRecordBatch deserializedBatch =
                MessageSerializer.deserializeRecordBatch(
                        new ReadChannel(
                                new ByteArrayReadableSeekableByteChannel(
                                        batch.getSerializedRecordBatch().toByteArray())),
                        allocator);

        loader.load(deserializedBatch);
        deserializedBatch.close();

        List<JsonNode> rows = new ArrayList<>();
        for (int i = 0; i < root.getRowCount(); i++) {
            rows.add(objectMapper.readTree(root.contentToJSONString(i)));
        }

        root.clear();
        return rows;
    }

    @Override
    public void close() {
        root.close();
        allocator.close();
    }
}
