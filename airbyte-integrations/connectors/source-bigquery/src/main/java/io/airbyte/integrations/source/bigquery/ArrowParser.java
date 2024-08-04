package io.airbyte.integrations.source.bigquery;

import static org.apache.arrow.vector.ipc.message.MessageSerializer.deserializeRecordBatch;
import static org.apache.arrow.vector.ipc.message.MessageSerializer.deserializeSchema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.storage.v1.ArrowRecordBatch;
import com.google.cloud.bigquery.storage.v1.ArrowSchema;
import com.google.protobuf.ByteString;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorLoader;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.ipc.ReadChannel;
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;

// A parser intended for one thread to read through multiple batches of Arrow data in sequence,
// returning each row as json.
class ArrowParser implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BufferAllocator allocator;
    private final VectorLoader loader;

    // A single root is used, the content of which will change as batches are loaded and cleared.
    // Only a single batch will ever be loaded at a time.
    private final VectorSchemaRoot root;

    // The previous iterator returned from processRows(). Held so it can be invalidated once
    private ArrowIterator previous;

    // Creates a parser for data with the specified schema.
    public ArrowParser(ArrowSchema arrowSchema, BufferAllocator allocator) {
        this.allocator = allocator;

        Schema schema = deserializeSchema(byteStringReader(arrowSchema.getSerializedSchema()));
        List<FieldVector> vectors = new ArrayList<>();
        for (Field f : schema.getFields()) {
            vectors.add(f.createVector(allocator));
        }
        this.root = new VectorSchemaRoot(vectors);
        this.loader = new VectorLoader(this.root);
    }

    // Load a batch for parsing, returning an Iterator over parsed data. Only one batch may
    // be loaded at a time per ArrowParser. Loading a new batch clears the previous one, and
    // invalidates the previous iterator. Closing the parser also invalidates the iterator.
    // An invalidated iterator will throw IllegalStateException from hasNext() and next().
    public Iterator<JsonNode> processRows(ArrowRecordBatch arrowBatch) throws IOException {
        if (previous != null) {
            previous.invalidate();
            this.root.clear();
        }

        org.apache.arrow.vector.ipc.message.ArrowRecordBatch batch = deserializeRecordBatch(
            byteStringReader(arrowBatch.getSerializedRecordBatch()),
            allocator
        );
        loader.load(batch);
        batch.close();

        previous = new ArrowIterator(root);
        return previous;
    }

    // Closes the Parser, invalidating the last iterator returned.
    @Override public void close() {
        if (prvious != null) {
            previous.invalidate();
        }
        root.close();
    }

    // Helper for creating an Arrow ReadChannel over a protobuf ByteString.
    private ReadChannel byteStringReader(ByteString bytes) {
        return new ReadChannel(new ByteArrayReadableSeekableByteChannel(bytes.toByteArray())))
    }

    // Iterator over a batch of rows in a VectorSchemaRoot, which returns them parsed to JSON.
    // If data in the root is modified, invalidate() should be called and any further attempts
    // to use the iterator (including calls to hasNext()) will throw IllegalStateException.
    private static ArrowIterator implements Iterator<JsonNode> {
        private final VectorSchemaRoot root;

        private boolean valid;
        private int i;

        ArrowIterator(VectorSchemaRoot root) {
            valid = true;
            i = 0;
        }

        @Override public boolean hasNext() {
            checkValid();
            return i < root.getRowCount();
        }

        @Override public JsonNode next() {
            checkValid();
            return MAPPER.readTree(root.contentToJSONString(i++));
        }

        public void invalidate() {
            valid = false;
        }

        private void checkValid() {
            if (!valid) {
                throw new IllegalStateException(
                    "Iterator from ArrowParser no longer usable because a new batch was loaded.");
            }
        }
    }
}
