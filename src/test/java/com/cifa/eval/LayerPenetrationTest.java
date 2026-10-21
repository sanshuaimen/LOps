package com.cifa.eval;

import com.cifa.llm.FakeLLMProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 5a: the corpus "layer" field must penetrate all four report classes —
 * GoldenCase (load + validation) → EvalResult → EvalReport (filter) →
 * ComparisonReport (per-layer breakdown rows).
 */
class LayerPenetrationTest {

    private static final Path FIXTURES = Path.of("fixtures/eval");

    @TempDir
    Path temp;

    @Test
    void unknownLayerIsRejectedAtLoadTime() throws Exception {
        Path caseFile = temp.resolve("case.json");
        Files.writeString(caseFile, """
                {"id": "bogus-layer", "layer": "unknown-layer", "expectedCategory": "UNKNOWN"}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GoldenCase.load(caseFile));
        assertTrue(error.getMessage().contains("unknown-layer"));
        assertTrue(error.getMessage().contains("accept"), "message must name the valid layers");
    }

    @Test
    void layerFlowsThroughEvalResultAndReport() throws Exception {
        List<GoldenCase> cases = GoldenCase.loadDirectory(FIXTURES, "security");
        assertFalse(cases.isEmpty(), "security layer must be non-empty");

        EvalReport report = new EvalRunner().runBaseline(cases, new FakeLLMProvider());

        assertEquals(cases.size(), report.results().size());
        assertTrue(report.results().stream().allMatch(result -> result.layer().equals("security")),
                "EvalResult must carry the case layer");
        assertEquals(List.of("security"), report.layers());
        assertTrue(report.toJson().contains("\"layer\""), "json output must expose the layer");
    }

    @Test
    void filterByLayerScopesResultsAndPreservesLayerOrder() throws Exception {
        List<GoldenCase> cases = GoldenCase.loadDirectory(FIXTURES);
        EvalReport report = new EvalRunner().runBaseline(cases, new FakeLLMProvider());

        List<String> layers = report.layers();
        assertEquals(Set.copyOf(GoldenCase.knownLayers()), Set.copyOf(layers),
                "full-corpus run sees every layer");
        assertEquals(layers, report.layers(), "layer order must be stable (first-seen)");
        assertEquals(cases.stream().filter(c -> c.layer().equals("regression")).count(),
                report.filterByLayer("regression").results().size());
        assertTrue(report.filterByLayer("regression").results().stream()
                .allMatch(result -> result.layer().equals("regression")));
        assertEquals(report.subject() + "/regression", report.filterByLayer("regression").subject());
        // Summaries must agree: layer-scoped passed count == full report restricted to that layer.
        long expectedPassed = report.results().stream()
                .filter(result -> result.layer().equals("regression") && result.passed()).count();
        assertEquals(expectedPassed, report.filterByLayer("regression").passedCount());
    }

    @Test
    void comparisonReportRendersPerLayerBreakdown() throws Exception {
        List<GoldenCase> cases = GoldenCase.loadDirectory(FIXTURES);
        EvalRunner runner = new EvalRunner();
        EvalReport baseline = runner.runBaseline(cases, new FakeLLMProvider());
        EvalReport agent = runner.runBaseline(cases, new FakeLLMProvider());

        String markdown = new ComparisonReport(baseline, agent).renderMarkdown();

        assertTrue(markdown.contains("## Per-layer breakdown"), "layer grouping row missing");
        for (String layer : GoldenCase.knownLayers()) {
            assertTrue(markdown.contains("| " + layer + " |"), "layer row missing: " + layer);
        }
        // accept carries the path-sensitive acceptance set: the row must be present
        // with baseline AND agent case counts equal to the layer size.
        long acceptCount = cases.stream().filter(c -> c.layer().equals("accept")).count();
        assertTrue(markdown.matches("(?s).*\\| accept \\| " + acceptCount + "/" + acceptCount + " \\|.*"),
                "accept row must show " + acceptCount + "/" + acceptCount);
    }
}
