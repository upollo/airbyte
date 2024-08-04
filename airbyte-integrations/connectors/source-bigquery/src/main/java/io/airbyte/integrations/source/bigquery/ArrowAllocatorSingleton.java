package io.airbyte.integrations.source.bigquery;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;

public class ArrowAllocatorSingleton {
    // Reading in batches, we don't expect any individual batch to be over 1GB.
    private static final long MAX_ALLOCATION_SIZE = 1024 * 1024 * 1024;

    private static volatile BufferAllocator instance;

    public static BufferAllocator getInstance() {
        if (this.instance == null) {
            synchronized (ArrowAllocatorSingleton.class) {
                if (this.instance == null) {
                    instance = new RootAllocator(MAX_ALLOCATION_SIZE);
                }
            }
        }
        return this.instance;
    }

    private ArrowAllocatorSingleton() {}
}
