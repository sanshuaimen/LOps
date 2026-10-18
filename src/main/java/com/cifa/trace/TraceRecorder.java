package com.cifa.trace;

import java.io.IOException;

public interface TraceRecorder extends AutoCloseable {
    void record(TraceEvent event);

    @Override
    default void close() throws IOException {
    }
}
