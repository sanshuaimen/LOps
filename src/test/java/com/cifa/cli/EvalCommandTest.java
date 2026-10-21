package com.cifa.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 7: the real-model eval entry. CI-visible surface only: --help, argument
 * validation, clear credential errors before any case runs, and the full fake
 * path end to end. Real providers are local manual commands and are never
 * exercised here (the ollama/anthropic branches preflight-fail without
 * credentials — exactly the "no half-crash" contract).
 */
class EvalCommandTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int eval(String[] args, Map<String, String> env) {
        return EvalCommand.run(args, new PrintStream(out), new PrintStream(err), env);
    }

    private String[] baseArgs(String... extra) {
        String[] args = new String[extra.length + 2];
        args[0] = "--provider";
        args[1] = "fake";
        System.arraycopy(extra, 0, args, 2, extra.length);
        return args;
    }

    @Test
    void helpExitsZeroAndDocumentsTheFlags() {
        assertEquals(0, eval(new String[]{"--help"}, Map.of()));
        String usage = out.toString();
        assertTrue(usage.contains("--provider fake|ollama|anthropic"));
        assertTrue(usage.contains("--runs"));
        assertTrue(usage.contains("--layer"));
        assertTrue(usage.contains("ANTHROPIC_API_KEY"));
        assertEquals(0, eval(new String[]{"-h"}, Map.of()));
    }

    @Test
    void argumentValidationFailsFastWithExitTwo() {
        assertEquals(2, eval(new String[]{}, Map.of()), "missing --provider");
        assertTrue(err.toString().contains("--provider is required"));

        assertEquals(2, eval(new String[]{"--provider", "claude"}, Map.of()));
        assertTrue(err.toString().contains("unsupported --provider 'claude'"));

        assertEquals(2, eval(new String[]{"--provider", "fake", "--layer", "bogus"}, Map.of()));
        assertTrue(err.toString().contains("unknown --layer 'bogus'"));

        assertEquals(2, eval(new String[]{"--provider", "fake", "--runs", "0"}, Map.of()));
        assertTrue(err.toString().contains("--runs must be >= 1"));

        assertEquals(2, eval(new String[]{"--provider", "fake", "--runs", "x"}, Map.of()));
        assertTrue(err.toString().contains("--runs expects an integer"));

        assertEquals(2, eval(new String[]{"--provider", "fake", "--model"}, Map.of()));
        assertTrue(err.toString().contains("--model expects a value"));

        assertEquals(2, eval(new String[]{"--provider", "fake", "--wat"}, Map.of()));
        assertTrue(err.toString().contains("unknown option '--wat'"));
    }

    @Test
    void missingAnthropicKeyIsAClearErrorBeforeAnyCaseRuns() {
        int exit = eval(new String[]{"--provider", "anthropic"}, Map.of());

        assertEquals(2, exit);
        String message = err.toString();
        assertTrue(message.contains("ANTHROPIC_API_KEY"), message);
        assertTrue(message.contains("environment"), message);
        assertTrue(out.toString().isEmpty(), "no case may run before the credential check");
    }

    @Test
    void unreachableOllamaIsAClearPreflightError() {
        // Nothing listens on the closed port; the preflight must convert the
        // connect failure into the documented exit-2 message, not a stack trace.
        int exit = eval(new String[]{"--provider", "ollama", "--model", "test-model",
                "--layer", "security"},
                Map.of("OLLAMA_URL", "http://127.0.0.1:9"));

        assertEquals(2, exit);
        assertTrue(err.toString().contains("Ollama not reachable at http://127.0.0.1:9"));
        assertTrue(out.toString().isEmpty());
    }

    @Test
    void fakeRunProducesVersionedReportOverALayerSlice(@TempDir Path outDir) throws Exception {
        int exit = eval(baseArgs("--layer", "security", "--out", outDir.toString()), Map.of());

        assertEquals(0, exit, () -> "stderr: " + err);
        String output = out.toString();
        assertTrue(output.contains("(fake)"), "fake runs must be labeled as such");

        try (var files = Files.list(outDir)) {
            Path report = files.filter(p -> p.getFileName().toString().startsWith("comparison-"))
                    .findFirst().orElseThrow(() -> new AssertionError("no comparison report written"));
            String markdown = Files.readString(report);
            assertTrue(markdown.contains("dataset version:"));
            assertTrue(markdown.contains("code version:"));
            assertTrue(markdown.contains("provider: fake"));
            assertTrue(markdown.contains("Fake / recorded responses"),
                    "fake disclaimer must be present");
            assertTrue(markdown.contains("| security |"), "per-layer row for the run slice");
            assertFalse(markdown.contains("REAL model runs"));
        }
    }

    @Test
    void fakeRunOverAllLayersWithMultipleRunsAppendsAggregation(@TempDir Path outDir) throws Exception {
        int exit = eval(baseArgs("--layer", "all", "--runs", "2", "--out", outDir.toString()), Map.of());

        assertEquals(0, exit, () -> "stderr: " + err);
        try (var files = Files.list(outDir)) {
            List<Path> reports = files.filter(p -> p.getFileName().toString().startsWith("comparison-")).toList();
            assertEquals(2, reports.size(), "one versioned report per run");
            // The --runs aggregation is stamped on the final run's report.
            String aggregated = null;
            for (Path report : reports) {
                String markdown = Files.readString(report);
                if (markdown.contains("## Run aggregation (--runs 2)")) {
                    aggregated = markdown;
                }
            }
            assertNotNull(aggregated, "final report must carry the --runs 2 aggregation");
            assertTrue(aggregated.contains("### Per category"));
            assertTrue(aggregated.contains("### Per case"));
        }
    }
}
