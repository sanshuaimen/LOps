package com.cifa.trace;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonlTraceRecorderTest {

    @Test
    void writesMachineReadableJsonlAndRedactsSecrets(@TempDir Path tempDir) throws Exception {
        Path trace = tempDir.resolve("nested/trace.jsonl");
        try (JsonlTraceRecorder recorder = new JsonlTraceRecorder(trace)) {
            recorder.record(new TraceEvent("task-1", 1, Instant.parse("2026-01-01T00:00:00Z"),
                    "tool_call", 2, "fake", "p0", "check evidence", "source_location",
                    "path=src/A.java token=secret-value", List.of("SRC-001"), 12, 8,
                    "ok", "", ""));
        }

        List<String> lines = Files.readAllLines(trace);
        assertEquals(1, lines.size());
        JSONObject json = new JSONObject(lines.get(0));
        assertEquals("task-1", json.getString("taskId"));
        assertEquals("source_location", json.getString("toolName"));
        assertEquals("SRC-001", json.getJSONArray("evidenceIds").getString(0));
        assertFalse(lines.get(0).contains("secret-value"));
        assertTrue(lines.get(0).contains("REDACTED"));
    }

    @Test
    void truncatesLargeTextAndKeepsEventSequence(@TempDir Path tempDir) throws Exception {
        Path trace = tempDir.resolve("trace.jsonl");
        try (JsonlTraceRecorder recorder = new JsonlTraceRecorder(trace)) {
            recorder.record(new TraceEvent("task", 7, Instant.now(), "model", 1, "", "", "x".repeat(3000),
                    "", "", List.of(), 0, 0, "", "", ""));
        }
        JSONObject json = new JSONObject(Files.readString(trace).trim());
        assertEquals(7, json.getLong("sequence"));
        assertTrue(json.getString("hypothesis").endsWith("[truncated]"));
    }

    @Test
    void memoryRecorderReturnsSnapshot() {
        InMemoryTraceRecorder recorder = new InMemoryTraceRecorder();
        recorder.record(TraceEvent.of("task", 1, "started"));
        assertEquals(1, recorder.events().size());
        assertEquals("started", recorder.events().get(0).eventType());
    }
}
