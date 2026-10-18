package com.cifa.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InMemoryTraceRecorder implements TraceRecorder {
    private final List<TraceEvent> events = new ArrayList<>();

    @Override
    public synchronized void record(TraceEvent event) {
        events.add(event);
    }

    public synchronized List<TraceEvent> events() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }
}
