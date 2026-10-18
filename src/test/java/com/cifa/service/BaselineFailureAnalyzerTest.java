package com.cifa.service;

import com.cifa.classification.FailureCategory;
import com.cifa.context.ContextBudget;
import com.cifa.llm.AnalysisResult;
import com.cifa.report.MarkdownRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BaselineFailureAnalyzerTest {

    @Test
    void independentlyProducesTheOriginalSingleCallReport(@TempDir Path repoRoot) throws Exception {
        Path log = repoRoot.resolve("build.log");
        Files.writeString(log, "[ERROR] FAILED: smoke test\n");
        PreparedDiagnosis prepared = new DiagnosisTaskService().prepare(
                new DiagnosisRequest(log, repoRoot, ContextBudget.DEFAULT));
        AnalysisResult expected = new AnalysisResult(
                "The smoke test failed", List.of("FAILED: smoke test"), "Inspect the test", 40, false);
        AtomicInteger calls = new AtomicInteger();

        BaselineResult result = new BaselineFailureAnalyzer().analyze(prepared, context -> {
            calls.incrementAndGet();
            assertSame(prepared.context(), context);
            return expected;
        });

        assertEquals(1, calls.get());
        assertSame(expected, result.analysis());
        assertEquals(FailureCategory.UNKNOWN, result.preparation().classified().category());
        String rendered = new MarkdownRenderer().render(
                result.preparation().classified().category(), result.analysis(), result.unverifiedEvidence());
        assertTrue(rendered.contains("The smoke test failed"));
        assertTrue(rendered.contains("FAILED: smoke test"));
    }

    @Test
    void requestOverloadRunsPreparationAndAnalysis(@TempDir Path repoRoot) throws Exception {
        Path log = repoRoot.resolve("build.log");
        Files.writeString(log, "[ERROR] FAILED: smoke test\n");
        BaselineResult result = new BaselineFailureAnalyzer().analyze(
                new DiagnosisRequest(log, repoRoot, ContextBudget.DEFAULT),
                context -> new AnalysisResult("cause", List.of(), "fix", 10, false));

        assertEquals("cause", result.analysis().rootCause());
        assertEquals("UNKNOWN", result.preparation().context().errorType());
    }
}
