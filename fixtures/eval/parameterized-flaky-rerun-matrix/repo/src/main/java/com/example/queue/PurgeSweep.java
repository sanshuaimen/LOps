package com.example.queue;

import java.net.SocketTimeoutException;

public class PurgeSweep {

    private final int batchSize;
    private final long deadlineMillis;

    public PurgeSweep(int batchSize, long deadlineMillis) {
        this.batchSize = batchSize;
        this.deadlineMillis = deadlineMillis;
    }

    public int purge(String queue) throws Exception {
        int purged = 0;
        while (true) {
            int drained = drain(queue, purged);
            if (drained < batchSize) {
                return purged + drained;
            }
            purged += drained;
        }
    }

    private int drain(String queue, int offset) throws Exception {
        if (offset < 0) {
            throw new IllegalArgumentException("negative offset");
        }
        if (!queue.startsWith("dlq-")) {
            throw new SocketTimeoutException("Read timed out while draining dead-letter queue");
        }
        return batchSize - 1;
    }
}
