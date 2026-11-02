package com.cifa.cli;

import com.cifa.memory.FailureMemoryEntry;
import com.cifa.memory.FailureMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryCommandDistillTest {

    private static final String LESSON_JSON = """
            {"worth_storing":true,"category":"DEPENDENCY_BUILD",
             "signature":"[ERROR] Failed to execute goal ... Could not resolve dependencies",
             "exception_type":"MavenResolutionException",
             "keywords":["dependency","resolve","maven"],
             "pattern":"Dependency resolution failure caused by a parent POM version that no longer exists upstream",
             "lesson":"Pin the parent POM to a published version or add the missing repository; check 'mvn dependency:tree' before editing module POMs.",
             "reason":"version-pin practice is not derivable from the log alone"}
            """;

    private static Path write(Path temp, String name, String content) throws Exception {
        Path path = temp.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    @Test
    void distillAddsEntryEndToEnd(@TempDir Path temp) throws Exception {
        Path trace = write(temp, "agent-trace.jsonl",
                "{\"taskId\":\"task-x\",\"seq\":1,\"eventType\":\"round\"}\n"
                        + "{\"taskId\":\"task-x\",\"seq\":2,\"eventType\":\"conclusion\"}\n");
        Path script = write(temp, "script.json", "[" + LESSON_JSON + "]");
        Path memoryFile = temp.resolve("memory.json");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = MemoryCommand.run(new String[]{
                "distill", "--trace", trace.toString(), "--task-id", "task-x",
                "--provider", "fake", "--fake-script", script.toString(),
                "--file", memoryFile.toString()},
                new java.io.PrintStream(out), new java.io.PrintStream(ByteArrayOutputStream.nullOutputStream()));

        assertEquals(0, exit, () -> out.toString());
        assertTrue(out.toString().contains("added:"));
        FailureMemoryStore store = new FailureMemoryStore(memoryFile);
        assertEquals(1, store.size());
        FailureMemoryEntry stored = store.entries().get(0);
        assertEquals("distilled", stored.layer());
        assertEquals("task-x", stored.caseId());
    }

    @Test
    void skipIsANormalReportedOutcome(@TempDir Path temp) throws Exception {
        Path trace = write(temp, "agent-trace.jsonl", "{\"taskId\":\"task-x\"}\n");
        Path script = write(temp, "script.json",
                "[{\"worth_storing\":false,\"reason\":\"one-off environment outage\"}]");
        Path memoryFile = temp.resolve("memory.json");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = MemoryCommand.run(new String[]{
                "distill", "--trace", trace.toString(), "--task-id", "task-x",
                "--provider", "fake", "--fake-script", script.toString(),
                "--file", memoryFile.toString()},
                new java.io.PrintStream(out), new java.io.PrintStream(ByteArrayOutputStream.nullOutputStream()));

        assertEquals(0, exit);
        assertTrue(out.toString().contains("distill skipped:"));
        assertTrue(out.toString().contains("one-off environment outage"));
        assertFalse(Files.exists(memoryFile));
    }

    @Test
    void mixedScriptDrivesDistillThenUpdateAgainstSeededEntry(@TempDir Path temp) throws Exception {
        // Regression for a smoke-caught integration bug: recorded() scripts are
        // parsed as AgentDecisions at load time, so a consolidate payload keyed
        // "action" was rejected before the distiller ever saw it. The consolidate
        // wire key is "decision"; this test pins the full mixed-script flow.
        Path trace = write(temp, "agent-trace.jsonl", "{\"taskId\":\"task-y\"}\n");
        Path memoryFile = temp.resolve("memory.json");
        FailureMemoryStore store = new FailureMemoryStore(memoryFile);
        store.append(new FailureMemoryEntry("dist-seed", "task-old", "distilled",
                "[ERROR] Failed to execute goal ... Could not resolve dependencies",
                "MavenResolutionException", List.of("dependency", "resolve", "maven"),
                "DEPENDENCY_BUILD", "old pattern", List.of(), "old lesson", "distilled:test"));

        Path script = write(temp, "script.json", "[" + LESSON_JSON + ","
                + "{\"decision\":\"update\",\"target_id\":\"dist-seed\","
                + "\"reason\":\"merge into the seeded entry\","
                + "\"merged\":{\"category\":\"DEPENDENCY_BUILD\","
                + "\"signature\":\"[ERROR] Failed to execute goal ... Could not resolve dependencies\","
                + "\"exception_type\":\"MavenResolutionException\","
                + "\"keywords\":[\"dependency\",\"resolve\",\"maven\",\"parent-pom\"],"
                + "\"pattern\":\"Dependency resolution failure from a vanished parent POM version\","
                + "\"lesson\":\"Pin the parent POM to a published version; verify with mvn dependency:tree.\"}}]");
        Path newTrace = write(temp, "trace2.jsonl", "{\"taskId\":\"task-y\"}\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = MemoryCommand.run(new String[]{
                "distill", "--trace", newTrace.toString(), "--task-id", "task-y",
                "--provider", "fake", "--fake-script", script.toString(),
                "--file", memoryFile.toString()},
                new java.io.PrintStream(out), new java.io.PrintStream(ByteArrayOutputStream.nullOutputStream()));

        assertEquals(0, exit, () -> out.toString());
        assertTrue(out.toString().contains("updated: dist-seed"), () -> out.toString());
        FailureMemoryStore reloaded = new FailureMemoryStore(memoryFile);
        assertEquals(1, reloaded.size());
        assertEquals("Pin the parent POM to a published version; verify with mvn dependency:tree.",
                reloaded.byId("dist-seed").orElseThrow().remediation());
    }

    @Test
    void unknownProviderNameIsRejectedBeforeAnythingRuns(@TempDir Path temp) throws Exception {
        Path trace = write(temp, "agent-trace.jsonl", "{}\n");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = MemoryCommand.run(new String[]{
                "distill", "--trace", trace.toString(), "--task-id", "task-x",
                "--provider", "nope", "--file", temp.resolve("memory.json").toString()},
                new java.io.PrintStream(ByteArrayOutputStream.nullOutputStream()),
                new java.io.PrintStream(err));

        assertEquals(2, exit);
        assertTrue(err.toString().contains("unsupported --provider"));
        assertFalse(Files.exists(temp.resolve("memory.json")));
    }
}
