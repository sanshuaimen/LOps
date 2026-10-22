package com.cifa.agent;

import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.evidence.EvidenceVerifier;
import com.cifa.llm.AgentDecision;
import com.cifa.llm.FakeLLMProvider;
import com.cifa.security.RepositoryPathPolicy;
import com.cifa.tool.InvestigationTool;
import com.cifa.tool.ToolRegistry;
import com.cifa.tool.ToolResult;
import com.cifa.tool.ToolStatus;
import com.cifa.trace.InMemoryTraceRecorder;
import com.cifa.trace.TraceEvent;
import com.cifa.llm.ToolSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InvestigationAgentLoopTest {

    @TempDir
    Path repo;

    private EvidenceCatalog catalog;
    private InMemoryTraceRecorder trace;

    private InvestigationAgentLoop loop(FakeLLMProvider provider, InvestigationBudget budget) {
        catalog = new EvidenceCatalog("task-1");
        trace = new InMemoryTraceRecorder();
        EvidenceVerifier verifier = new EvidenceVerifier(catalog, new RepositoryPathPolicy(repo));
        ToolRegistry tools = new ToolRegistry().register(new EchoTool(catalog));
        return new InvestigationAgentLoop(provider, tools, catalog, verifier, trace, budget, "task-1", "fake-model");
    }

    /** A minimal controlled tool: registers one SOURCE-style evidence per call. */
    private static final class EchoTool implements InvestigationTool {
        private final String name;
        private final EvidenceCatalog catalog;

        private EchoTool(EvidenceCatalog catalog) {
            this("echo", catalog);
        }

        private EchoTool(String name, EvidenceCatalog catalog) {
            this.name = name;
            this.catalog = catalog;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "echoes a finding into the evidence catalog";
        }

        @Override
        public ToolSchema schema() {
            return ToolSchema.object(name, description());
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            String finding = String.valueOf(arguments.getOrDefault("finding", "nothing"));
            if (finding.equals("fail")) {
                return ToolResult.failed("injected failure");
            }
            var evidence = catalog.register(EvidenceType.LOG, "normalized_log:1-2", finding,
                    name, 1, 2, "");
            return ToolResult.ok(finding, List.of(evidence.id()), 0);
        }
    }

    private static AgentDecision submit() {
        return AgentDecision.submit("LOGIC_ERROR", "null check missing in UserService",
                List.of("LOG-001"), 80, "add null guard");
    }

    @Test
    void completesOneInvestigationRoundWithToolThenSubmit() {
        FakeLLMProvider provider = new FakeLLMProvider(
                AgentDecision.toolCall("echo", Map.of("finding", "NPE at UserService.java:9"), "hypothesis: NPE in service"),
                submit());
        InvestigationAgentLoop loop = loop(provider, InvestigationBudget.defaults());

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop, "investigate the failure");

        assertEquals(AgentStopReason.COMPLETED, outcome.stopReason());
        assertNotNull(outcome.diagnosis());
        assertFalse(outcome.diagnosis().unknown());
        assertEquals("LOGIC_ERROR", outcome.diagnosis().category());
        assertEquals(List.of("LOG-001"), outcome.diagnosis().evidenceIds());
        // One round for the tool call, one for the submit.
        assertEquals(2, outcome.state().round());
        assertTrue(outcome.state().tokensUsed() > 0);
        assertTrue(trace.events().stream().anyMatch(event -> event.eventType().equals("tool_call")));
        assertTrue(trace.events().stream().anyMatch(event -> event.eventType().equals("conclusion")));
    }

    @Test
    void unknownDecisionEndsWithInsufficientEvidence() {
        InvestigationAgentLoop loop = loop(new FakeLLMProvider(
                AgentDecision.unknown("log has no stack trace and no test reports exist")),
                InvestigationBudget.defaults());

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop, "investigate");

        assertEquals(AgentStopReason.INSUFFICIENT_EVIDENCE, outcome.stopReason());
        assertTrue(outcome.diagnosis().unknown());
        assertEquals("", outcome.diagnosis().suggestedAction());
    }

    @Test
    void fabricatingEvidenceIdsGetsRejectedThenModelCanRecover() {
        FakeLLMProvider provider = new FakeLLMProvider(
                AgentDecision.submit("LOGIC_ERROR", "made up cause", List.of("LOG-999"), 90, "fix it"),
                AgentDecision.toolCall("echo", Map.of("finding", "real evidence")),
                submit());
        InvestigationAgentLoop loop = loop(provider, InvestigationBudget.defaults());

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop, "investigate");

        assertEquals(AgentStopReason.COMPLETED, outcome.stopReason());
        assertEquals("LOG-001", outcome.diagnosis().evidenceIds().get(0));
        assertTrue(provider.requests().stream()
                .anyMatch(request -> request.userPrompt().contains("LOG-999")));
    }

    @Test
    void submissionWithoutEvidenceIsRejectedByConclusionPolicy() {
        FakeLLMProvider provider = new FakeLLMProvider(
                AgentDecision.submit("LOGIC_ERROR", "guessing", List.of(), 90, ""),
                AgentDecision.unknown("no real evidence available"));
        InvestigationAgentLoop loop = loop(provider, InvestigationBudget.defaults());

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop, "investigate");

        assertEquals(AgentStopReason.INSUFFICIENT_EVIDENCE, outcome.stopReason());
        assertTrue(provider.requests().stream()
                .anyMatch(request -> request.userPrompt().contains("submission rejected")));
    }

    @Test
    void systemPromptCarriesTheTriageDefinitionsNotJustLabelNames() {
        // Real-model finding: a bare label list let the model classify by plain-English
        // intuition (compile error -> LOGIC_ERROR). The prompt must carry the stage-based
        // triage definitions, kept in sync from the FailureCategory enum itself.
        FakeLLMProvider provider = new FakeLLMProvider(AgentDecision.unknown("stop early"));
        InvestigationAgentLoop loop = loop(provider, InvestigationBudget.defaults());
        runQuietly(loop, "investigate");

        String systemPrompt = provider.requests().get(0).systemPrompt();
        assertTrue(systemPrompt.contains("the build refused to produce artifacts"),
                "DEPENDENCY_BUILD definition missing: " + systemPrompt);
        assertTrue(systemPrompt.contains("behaved wrongly at runtime"), "LOGIC_ERROR definition missing");
        assertTrue(systemPrompt.contains("clean reruns tend to clear"), "INFRA_FLAKY definition missing");
        assertTrue(systemPrompt.contains("failure STAGE"), "stage-vs-mistake boundary rule missing");
    }

    @Test
    void stopsAtMaxRoundsAndProducesUnknownDiagnosis() {
        FakeLLMProvider provider = new FakeLLMProvider();
        for (int i = 0; i < 5; i++) {
            provider.enqueue(AgentDecision.toolCall("echo", Map.of("finding", "run " + i)));
        }
        InvestigationBudget budget = new InvestigationBudget(3, 50_000, Duration.ofMinutes(5), 3, 100, 2);
        InvestigationAgentLoop loop = loop(provider, budget);

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop, "investigate");

        assertEquals(AgentStopReason.MAX_ROUNDS, outcome.stopReason());
        assertTrue(outcome.diagnosis().unknown());
        assertEquals(3, provider.requests().size());
    }

    @Test
    void stopsOnConsecutiveToolFailures() {
        FakeLLMProvider provider = new FakeLLMProvider();
        for (int i = 0; i < 5; i++) {
            provider.enqueue(AgentDecision.toolCall("echo", Map.of("finding", "fail")));
        }
        InvestigationAgentLoop loop = loop(provider, InvestigationBudget.defaults());

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop, "investigate");

        assertEquals(AgentStopReason.CONSECUTIVE_TOOL_FAILURES, outcome.stopReason());
        assertEquals(3, outcome.state().consecutiveToolFailures());
    }

    @Test
    void consecutiveToolTimeoutsReachTheSameStopCondition() {
        // TIMEOUT is a first-class tool failure: subprocess-backed tools that time
        // out (or are budget-skipped by the registry) must reach the same stop
        // condition as FAILED results, not just silently burn rounds.
        catalog = new EvidenceCatalog("task-1");
        trace = new InMemoryTraceRecorder();
        EvidenceVerifier verifier = new EvidenceVerifier(catalog, new RepositoryPathPolicy(repo));
        ToolRegistry tools = new ToolRegistry().register(new InvestigationTool() {
            @Override
            public String name() {
                return "stalling_git";
            }

            @Override
            public String description() {
                return "stands in for a git tool whose subprocess timed out";
            }

            @Override
            public ToolSchema schema() {
                return ToolSchema.object("stalling_git", description());
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return ToolResult.timeout("git log timed out after the configured budget");
            }
        });
        FakeLLMProvider provider = new FakeLLMProvider();
        for (int i = 0; i < 5; i++) {
            provider.enqueue(AgentDecision.toolCall("stalling_git",
                    Map.of("path", "A.java", "operation", "log")));
        }
        InvestigationAgentLoop loop = new InvestigationAgentLoop(provider, tools, catalog, verifier,
                trace, InvestigationBudget.defaults(), "task-1", "fake-model");

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop, "investigate");

        assertEquals(AgentStopReason.CONSECUTIVE_TOOL_FAILURES, outcome.stopReason());
        assertEquals(3, outcome.state().consecutiveToolFailures());
        assertTrue(trace.events().stream().anyMatch(event -> event.status().equals("timeout")));
    }

    @Test
    void stopsOnRepeatedCallsWithoutProgress() {
        FakeLLMProvider provider = new FakeLLMProvider(
                AgentDecision.toolCall("echo", Map.of("finding", "same")),
                AgentDecision.toolCall("echo", Map.of("finding", "same")),
                AgentDecision.toolCall("echo", Map.of("finding", "same")));
        InvestigationAgentLoop loop = loop(provider, InvestigationBudget.defaults());

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop, "investigate");

        assertEquals(AgentStopReason.NO_PROGRESS, outcome.stopReason());
        assertTrue(trace.events().stream()
                .anyMatch(event -> event.stopReason().contains("repeat-without-progress")));
    }

    @Test
    void firstRepeatWarnsTheModelBeforeTheSecondStops() {
        // threshold boundary: repeat #1 feeds the warning back and continues,
        // repeat #2 (the configured max) stops the investigation
        FakeLLMProvider provider = new FakeLLMProvider();
        provider.enqueue(AgentDecision.toolCall("echo", Map.of("finding", "same")));
        provider.enqueue(AgentDecision.toolCall("echo", Map.of("finding", "same")));
        provider.enqueue(AgentDecision.toolCall("echo", Map.of("finding", "same")));
        InvestigationAgentLoop loop = loop(provider, InvestigationBudget.defaults());

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop, "investigate");

        assertEquals(AgentStopReason.NO_PROGRESS, outcome.stopReason());
        assertEquals(3, provider.requests().size());
        assertTrue(provider.requests().get(2).messages().stream()
                        .anyMatch(message -> message.content().contains("[warning] identical call")),
                "the no-progress warning must reach the model before the stop");
    }

    @Test
    void alternatingDistinctCallsAreNotNoProgressUntilMaxRounds() {
        // known limitation, pinned: the repeat detector is same-tool-same-args only,
        // so ever-different arguments are bounded by MAX_ROUNDS, not NO_PROGRESS
        FakeLLMProvider provider = new FakeLLMProvider();
        for (int i = 0; i < 8; i++) {
            provider.enqueue(AgentDecision.toolCall("echo", Map.of("finding", "run " + i)));
        }
        InvestigationAgentLoop loop = loop(provider, InvestigationBudget.defaults());

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop, "investigate");

        assertEquals(AgentStopReason.MAX_ROUNDS, outcome.stopReason());
        assertEquals(0, outcome.state().repeatsWithoutProgress());
    }

    @Test
    void sameArgumentsUnderDifferentToolsAreNotRepeats() {
        // the signature includes the tool name: tool_b(same) directly after
        // tool_a(same) is a fresh call, not a repeat
        FakeLLMProvider provider = new FakeLLMProvider();
        provider.enqueue(AgentDecision.toolCall("tool_a", Map.of("finding", "same")));
        provider.enqueue(AgentDecision.toolCall("tool_b", Map.of("finding", "same")));
        provider.enqueue(AgentDecision.unknown("done"));
        EvidenceCatalog sameArgsCatalog = new EvidenceCatalog("task-1");
        InMemoryTraceRecorder sameArgsTrace = new InMemoryTraceRecorder();
        EvidenceVerifier verifier = new EvidenceVerifier(sameArgsCatalog, new RepositoryPathPolicy(repo));
        ToolRegistry tools = new ToolRegistry()
                .register(new EchoTool("tool_a", sameArgsCatalog))
                .register(new EchoTool("tool_b", sameArgsCatalog));
        InvestigationAgentLoop loop = new InvestigationAgentLoop(provider, tools, sameArgsCatalog,
                verifier, sameArgsTrace, InvestigationBudget.defaults(), "task-1", "fake-model");

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop, "investigate");

        assertEquals(AgentStopReason.INSUFFICIENT_EVIDENCE, outcome.stopReason());
        assertEquals(0, outcome.state().repeatsWithoutProgress());
        assertTrue(noRepeatWarnings(sameArgsTrace));
    }

    private static boolean noRepeatWarnings(InMemoryTraceRecorder recorder) {
        return recorder.events().stream()
                .noneMatch(event -> event.stopReason().contains("repeat-without-progress"));
    }

    @Test
    void tokenBudgetExhaustionStopsTheLoop() {
        FakeLLMProvider provider = new FakeLLMProvider(
                AgentDecision.toolCall("echo", Map.of("finding", "x")),
                AgentDecision.toolCall("echo", Map.of("finding", "x2")),
                AgentDecision.unknown("giving up"));
        InvestigationBudget budget = new InvestigationBudget(10, 1, Duration.ofMinutes(5), 3, 2, 2);
        InvestigationAgentLoop loop = loop(provider, budget);

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop, "investigate");

        assertEquals(AgentStopReason.TOKEN_BUDGET_EXHAUSTED, outcome.stopReason());
        assertEquals(1, provider.requests().size());
    }

    @Test
    void modelFailureAfterRetriesProducesStructuredFailure() {
        FakeLLMProvider provider = new FakeLLMProvider();
        provider.enqueueFailure(new java.io.IOException("api down"));
        provider.enqueueFailure(new java.io.IOException("api down"));
        provider.enqueueFailure(new java.io.IOException("api down"));
        InvestigationAgentLoop loop = loop(provider, InvestigationBudget.defaults());

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop, "investigate");

        assertEquals(AgentStopReason.MODEL_FAILURE, outcome.stopReason());
        assertNull(outcome.diagnosis());
        assertEquals(3, provider.requests().size());
        assertTrue(trace.events().stream().anyMatch(event -> event.eventType().equals("model_failure")));
    }

    @Test
    void recordsHypothesisOnEveryToolCallTrace() {
        FakeLLMProvider provider = new FakeLLMProvider(
                AgentDecision.toolCall("echo", Map.of("finding", "a"), "checking the null path"),
                submit());
        InvestigationAgentLoop loop = loop(provider, InvestigationBudget.defaults());

        runQuietly(loop, "investigate");

        TraceEvent toolEvent = trace.events().stream()
                .filter(event -> event.eventType().equals("tool_call"))
                .findFirst().orElseThrow();
        assertEquals("checking the null path", toolEvent.hypothesis());
        assertEquals("echo", toolEvent.toolName());
        assertEquals("finding=a", toolEvent.argumentsSummary());
    }

    @Test
    void systemPromptRendersToolCatalogFromTheRegistry() {
        FakeLLMProvider provider = new FakeLLMProvider(AgentDecision.unknown("stop"));
        InvestigationAgentLoop loop = loop(provider, InvestigationBudget.defaults());

        runQuietly(loop, "investigate");

        String systemPrompt = provider.requests().get(0).systemPrompt();
        assertTrue(systemPrompt.contains("Available tools"), () -> systemPrompt);
        assertTrue(systemPrompt.contains("echo"), () -> systemPrompt);
        assertTrue(systemPrompt.contains("echoes a finding into the evidence catalog"), () -> systemPrompt);
        assertTrue(systemPrompt.contains("finding"), () -> systemPrompt);
    }

    private static InvestigationAgentLoop.AgentOutcome runQuietly(
            InvestigationAgentLoop loop, String brief) {
        try {
            return loop.run(brief);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
