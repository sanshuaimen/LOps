package com.cifa.agent;

import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.evidence.EvidenceVerifier;
import com.cifa.llm.AgentDecision;
import com.cifa.llm.FakeLLMProvider;
import com.cifa.llm.RateLimitedException;
import com.cifa.security.RepositoryPathPolicy;
import com.cifa.tool.ToolRegistry;
import com.cifa.trace.InMemoryTraceRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crash-point regression tests: unparseable model output and decision-contract
 * violations must end in MODEL_FAILURE (or recovery), never escape run() as
 * raw exceptions.
 */
class ModelFailurePolicyTest {

    @TempDir
    Path repo;

    private InvestigationAgentLoop loop(FakeLLMProvider provider) {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        InMemoryTraceRecorder trace = new InMemoryTraceRecorder();
        EvidenceVerifier verifier = new EvidenceVerifier(catalog, new RepositoryPathPolicy(repo));
        ToolRegistry tools = new ToolRegistry(); // no tools: unknown tool calls are invalid args
        InvestigationBudget budget = new InvestigationBudget(8, 50_000,
                java.time.Duration.ofMinutes(5), 3, 2, 2);
        return new InvestigationAgentLoop(provider, tools, catalog, verifier, trace, budget, "task-1", "fake");
    }

    private static InvestigationAgentLoop.AgentOutcome runQuietly(InvestigationAgentLoop loop) {
        try {
            return loop.run("investigate");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void unparseableModelOutputIsRetriedThenRecovers() {
        FakeLLMProvider provider = new FakeLLMProvider();
        provider.enqueueRaw("this is not json at all");
        provider.enqueueRaw("still not json {");
        provider.enqueue(AgentDecision.unknown("recovered after parse failures"));
        InvestigationAgentLoop loop = loop(provider);

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop);

        assertEquals(AgentStopReason.INSUFFICIENT_EVIDENCE, outcome.stopReason());
        assertEquals(3, provider.requests().size());
    }

    @Test
    void unparseableModelOutputExhaustsRetriesIntoModelFailure() {
        FakeLLMProvider provider = new FakeLLMProvider();
        provider.enqueueRaw("bad 1");
        provider.enqueueRaw("bad 2");
        provider.enqueueRaw("bad 3");
        InvestigationAgentLoop loop = loop(provider);

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop);

        assertEquals(AgentStopReason.MODEL_FAILURE, outcome.stopReason());
        assertNull(outcome.diagnosis());
        assertEquals(3, provider.requests().size());
    }

    @Test
    void decisionContractViolationIsNotRetried() {
        FakeLLMProvider provider = new FakeLLMProvider();
        // Parses as a tool_call with a blank tool name -> IllegalArgumentException inside the provider.
        provider.enqueueRaw("{\"type\":\"tool_call\",\"arguments\":{}}");
        provider.enqueue(AgentDecision.unknown("never reached"));
        InvestigationAgentLoop loop = loop(provider);

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop);

        assertEquals(AgentStopReason.MODEL_FAILURE, outcome.stopReason());
        assertNull(outcome.diagnosis());
        assertEquals(1, provider.requests().size(), "contract violation must not be retried");
    }

    @Test
    void networkFailuresAreRetriedBeforeModelFailure() {
        FakeLLMProvider provider = new FakeLLMProvider();
        provider.enqueueFailure(new java.io.IOException("connection reset"));
        provider.enqueueFailure(new java.io.IOException("connection reset"));
        provider.enqueue(AgentDecision.unknown("ok now"));
        InvestigationAgentLoop loop = loop(provider);

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop);

        assertEquals(AgentStopReason.INSUFFICIENT_EVIDENCE, outcome.stopReason());
        assertEquals(3, provider.requests().size());
    }

    @Test
    void rateLimitFailuresExhaustIntoACategorizedModelFailure() {
        // RateLimitedException is an IOException subclass, so the retry count is
        // unchanged — what's new is the category travelling through the trace.
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        InMemoryTraceRecorder trace = new InMemoryTraceRecorder();
        EvidenceVerifier verifier = new EvidenceVerifier(catalog, new RepositoryPathPolicy(repo));
        FakeLLMProvider provider = new FakeLLMProvider();
        for (int i = 0; i < 3; i++) {
            provider.enqueueFailure(new RateLimitedException("fake upstream says 429"));
        }
        InvestigationAgentLoop loop = new InvestigationAgentLoop(provider, new ToolRegistry(),
                catalog, verifier, trace,
                new InvestigationBudget(8, 50_000, java.time.Duration.ofMinutes(5), 3, 2, 2),
                "task-1", "fake");

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop);

        assertEquals(AgentStopReason.MODEL_FAILURE, outcome.stopReason());
        assertNull(outcome.diagnosis());
        assertEquals(3, provider.requests().size(), "modelRetries=2 means 3 attempts");
        assertTrue(trace.events().stream().anyMatch(event -> event.eventType().equals("model_failure")
                && event.error().contains("gave up after 3 attempts")
                && event.error().contains("rate_limited")), () -> trace.events().toString());
        assertTrue(trace.events().stream().filter(event -> event.eventType().equals("model_retry"))
                .allMatch(event -> event.error().startsWith("rate_limited: ")));
    }
}
