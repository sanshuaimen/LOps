package com.cifa.service;

import com.cifa.agent.InvestigationBudget;
import com.cifa.classification.FailureCategory;
import com.cifa.llm.AnalysisResult;
import com.cifa.llm.LLMProvider;
import com.cifa.llm.LLMResponse;
import com.cifa.tool.FlakyHistorySource;
import com.cifa.tool.JsonFlakyHistorySource;
import com.cifa.trace.InMemoryTraceRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 降级链 contract, integration-grade over the REAL agent loop: agent → baseline
 * (same provider) → rules-only tail. The degrade decision is deterministic
 * service code; cancellation never degrades; every degraded outcome carries its
 * stage and categorized reason, traced like any other event.
 */
class FallbackDiagnosisOrchestratorTest {

    @TempDir
    Path repo;

    private static final FlakyHistorySource NO_HISTORY = JsonFlakyHistorySource.empty();
    private static final Path NPE_LOG = Path.of("fixtures/eval/npe-log/build.log");
    private static final Path NPE_REPO = Path.of("fixtures/eval/npe-log/repo");

    private DiagnosisRequest request() {
        return new DiagnosisRequest(NPE_LOG, NPE_REPO, com.cifa.context.ContextBudget.DEFAULT);
    }

    /** Agent path (invoke) dies into MODEL_FAILURE; baseline path (analyze) answers. */
    private static LLMProvider agentBrokenBaselineWorks() {
        return new LLMProvider() {
            @Override
            public AnalysisResult analyze(com.cifa.context.AnalysisContext context) {
                return new AnalysisResult("baseline root cause: null customer dereferenced",
                        List.of("UserService.java:9"), "guard the null path", 40, false);
            }

            @Override
            public LLMResponse invoke(com.cifa.llm.LLMRequest request) throws IOException {
                throw new IOException("provider down");
            }
        };
    }

    /** Everything is down: agent and baseline analyze both throw. */
    private static LLMProvider fullyBroken() {
        return new LLMProvider() {
            @Override
            public AnalysisResult analyze(com.cifa.context.AnalysisContext context) throws IOException {
                throw new IOException("provider hard down");
            }

            @Override
            public LLMResponse invoke(com.cifa.llm.LLMRequest request) throws IOException {
                throw new IOException("provider hard down");
            }
        };
    }

    @Test
    void healthyAgentAnswerIsNotDegraded() throws Exception {
        FallbackDiagnosisOrchestrator orchestrator = new FallbackDiagnosisOrchestrator();
        var outcome = orchestrator.diagnose(request(),
                com.cifa.llm.FakeLLMProvider.recorded(Path.of("fixtures/eval/npe-log/agent-script.json")),
                "fake", InvestigationBudget.defaults(), "t-1", new InMemoryTraceRecorder(), NO_HISTORY);

        assertFalse(outcome.degraded());
        assertEquals(FallbackDiagnosisOrchestrator.Stage.AGENT, outcome.stage());
        assertNotNull(outcome.agent().diagnosis());
        assertNull(outcome.baseline());
    }

    @Test
    void modelFailureFallsBackToBaselineLlmResult() throws Exception {
        FallbackDiagnosisOrchestrator orchestrator = new FallbackDiagnosisOrchestrator();
        InMemoryTraceRecorder trace = new InMemoryTraceRecorder();

        var outcome = orchestrator.diagnose(request(), agentBrokenBaselineWorks(),
                "fake", InvestigationBudget.defaults(), "t-2", trace, NO_HISTORY);

        assertEquals(FallbackDiagnosisOrchestrator.Stage.BASELINE_LLM, outcome.stage());
        assertTrue(outcome.degraded());
        assertTrue(outcome.fallbackReason().contains("MODEL_FAILURE"), outcome.fallbackReason());
        assertNotNull(outcome.agent(), "the dead agent attempt is preserved for audit");
        assertNull(outcome.agent().diagnosis());
        assertNotNull(outcome.baseline());
        assertTrue(outcome.baseline().analysis().rootCause().contains("baseline root cause"));
        assertTrue(trace.events().stream().anyMatch(e -> "fallback".equals(e.eventType())
                        && e.error() != null && e.error().contains("MODEL_FAILURE")),
                "fallback decision must be traced");
    }

    @Test
    void deadProviderFallsBackToRulesOnlyTail() throws Exception {
        FallbackDiagnosisOrchestrator orchestrator = new FallbackDiagnosisOrchestrator();
        InMemoryTraceRecorder trace = new InMemoryTraceRecorder();

        var outcome = orchestrator.diagnose(request(), fullyBroken(),
                "fake", InvestigationBudget.defaults(), "t-3", trace, NO_HISTORY);

        assertEquals(FallbackDiagnosisOrchestrator.Stage.BASELINE_RULES, outcome.stage());
        assertTrue(outcome.degraded());
        assertTrue(outcome.fallbackReason().contains("also failed"), outcome.fallbackReason());
        AnalysisResult rules = outcome.baseline().analysis();
        assertFalse(rules.lowConfidenceInfra(), "degradation is not an infra signal");
        assertEquals(0, rules.confidence());
        assertTrue(rules.rootCause().contains("Rule-engine fallback"));
        // The deterministic classifier still ran — category comes from rules, not the model.
        assertEquals(FailureCategory.LOGIC_ERROR,
                outcome.baseline().preparation().classified().category());
    }

    @Test
    void cancellationIsNeverConvertedIntoFallback() {
        LLMProvider interrupting = new LLMProvider() {
            @Override
            public AnalysisResult analyze(com.cifa.context.AnalysisContext context) {
                throw new AssertionError("must not reach baseline after cancellation");
            }

            @Override
            public LLMResponse invoke(com.cifa.llm.LLMRequest request) throws InterruptedException {
                throw new InterruptedException("task cancelled");
            }
        };

        FallbackDiagnosisOrchestrator orchestrator = new FallbackDiagnosisOrchestrator();

        assertThrows(InterruptedException.class, () -> orchestrator.diagnose(request(), interrupting,
                "fake", InvestigationBudget.defaults(), "t-4", new InMemoryTraceRecorder(), NO_HISTORY));
    }
}
