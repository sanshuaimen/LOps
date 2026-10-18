package com.cifa.trace;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class JsonlTraceRecorder implements TraceRecorder {
    private final BufferedWriter writer;
    private boolean closed;

    public JsonlTraceRecorder(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        this.writer = Files.newBufferedWriter(absolute, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override
    public synchronized void record(TraceEvent event) {
        if (closed) {
            throw new IllegalStateException("trace recorder is closed");
        }
        try {
            writer.write(event.toJson().toString());
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write trace event", e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            writer.close();
        }
    }
}
