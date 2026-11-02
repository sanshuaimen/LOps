package com.cifa.agent;

import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.evidence.EvidenceVerifier;
import com.cifa.llm.AgentDecision;
import com.cifa.llm.FakeLLMProvider;
import com.cifa.security.RepositoryPathPolicy;
import com.cifa.tool.ToolRegistry;
import com.cifa.trace.InMemoryTraceRecorder;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer-3 integration: the judge rides INSIDE the loop, after the
 * deterministic gates. Order and discipline pinned here:
 * gates first (a rule rejection never consults the judge), one send-back,
 * then a deterministic downgrade; judge malfunction never blocks.
 */
class JudgmentAgentLoopIntegrationTest {

    private EvidenceCatalog catalog;
    private InMemoryTraceRecorder trace;

    private InvestigationAgentLoop loop(FakeLLMProvider provider, JudgmentAgent judge) {
        catalog = new EvidenceCatalog("task-1");
        catalog.register(EvidenceType.LOG, "normalized_log:1-2",
                "java.lang.NullPointerException at UserService.java:9", "preprocessing", 1, 2, "");
        trace = new InMemoryTraceRecorder();
        EvidenceVerifier verifier = new EvidenceVerifier(catalog, new RepositoryPathPolicy(Path.of(".")));
        return new InvestigationAgentLoop(provider, new ToolRegistry(), catalog, verifier,
                trace, InvestigationBudget.defaults(), "task-1", "fake-model", judge);
    }

    private static AgentDecision submit() {
        return AgentDecision.submit("LOGIC_ERROR", "null check missing in UserService",
                List.of("LOG-001"), 80, "add null guard");
    }

    private static InvestigationAgentLoop.AgentOutcome runQuietly(InvestigationAgentLoop loop) {
        try {
            return loop.run("investigate the failure");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void supportedVerdictCompletesWithTheStamp() {
        FakeLLMProvider agent = new FakeLLMProvider(submit());
        JudgmentAgent judge = new JudgmentAgent(new RawJudgeProvider(
                "{\"verdict\":\"supported\",\"reason\":\"LOG-001 shows the NPE line\"}"), "fake-judge");

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop(agent, judge));

        assertEquals(AgentStopReason.COMPLETED, outcome.stopReason());
        assertFalse(outcome.diagnosis().unknown());
        assertEquals("SUPPORTED", outcome.diagnosis().judgeVerdict());
        assertTrue(trace.events().stream().anyMatch(event ->
                event.eventType().equals("judgment") && event.status().equals("supported")));
    }

    @Test
    void firstUnsupportedVerdictSendsTheSubmissionBackForRevision() {
        // The rejection feedback must reach the model with the judge's reason so
        // the revision can fix the chain instead of rewording the claim.
        FakeLLMProvider agent = new FakeLLMProvider(submit(), submit());
        JudgmentAgent judge = new JudgmentAgent(new RawJudgeProvider(
                "{\"verdict\":\"unsupported\",\"reason\":\"LOG-001 shows the trace, not the null assignment\"}",
                "{\"verdict\":\"supported\",\"reason\":\"revised chain holds\"}"), "fake-judge");

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop(agent, judge));

        assertEquals(AgentStopReason.COMPLETED, outcome.stopReason());
        assertEquals("SUPPORTED", outcome.diagnosis().judgeVerdict());
        assertTrue(agent.requests().get(1).messages().stream()
                        .anyMatch(message -> message.content().contains("[semantic review rejected]")),
                "the send-back must reach the model before the revision");
        assertTrue(trace.events().stream().anyMatch(event ->
                event.eventType().equals("judgment") && event.status().equals("unsupported")));
    }

    @Test
    void secondUnsupportedVerdictDowngradesToAnHonestUnknown() {
        FakeLLMProvider agent = new FakeLLMProvider(submit(), submit());
        JudgmentAgent judge = new JudgmentAgent(new RawJudgeProvider(
                "{\"verdict\":\"unsupported\",\"reason\":\"no assignment shown\"}",
                "{\"verdict\":\"unsupported\",\"reason\":\"still no assignment shown\"}"), "fake-judge");

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop(agent, judge));

        assertEquals(AgentStopReason.JUDGE_UNSUPPORTED, outcome.stopReason());
        assertTrue(outcome.diagnosis().unknown());
        assertEquals("UNSUPPORTED", outcome.diagnosis().judgeVerdict());
        assertEquals("", outcome.diagnosis().suggestedAction());
        assertTrue(outcome.diagnosis().rootCause().contains("semantic review"));
        assertTrue(trace.events().stream().anyMatch(event ->
                event.eventType().equals("conclusion")
                        && event.stopReason().equals("JUDGE_UNSUPPORTED")));
    }

    @Test
    void judgeMalfunctionNeverBlocksTheDiagnosis() {
        FakeLLMProvider agent = new FakeLLMProvider(submit());
        JudgmentAgent judge = new JudgmentAgent(new RawJudgeProvider(new java.io.IOException("judge api down")),
                "fake-judge");

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop(agent, judge));

        assertEquals(AgentStopReason.COMPLETED, outcome.stopReason());
        assertFalse(outcome.diagnosis().unknown());
        assertEquals("UNAVAILABLE", outcome.diagnosis().judgeVerdict());
        assertTrue(trace.events().stream().anyMatch(event ->
                event.eventType().equals("judgment") && event.status().equals("unavailable")));
    }

    @Test
    void inconclusiveVerdictStampsButDoesNotBlock() {
        FakeLLMProvider agent = new FakeLLMProvider(submit());
        JudgmentAgent judge = new JudgmentAgent(new RawJudgeProvider(
                "{\"verdict\":\"inconclusive\",\"reason\":\"evidence is ambiguous\"}"), "fake-judge");

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop(agent, judge));

        assertEquals(AgentStopReason.COMPLETED, outcome.stopReason());
        assertEquals("INCONCLUSIVE", outcome.diagnosis().judgeVerdict());
    }

    @Test
    void deterministicGatesFireBeforeTheJudgeIsConsulted() {
        // A fabricated citation must be caught by layer 1 WITHOUT burning a
        // judge call: if the judge ran here it would consume the scripted
        // unknown and the trace would carry a judgment event.
        FakeLLMProvider agent = new FakeLLMProvider(
                AgentDecision.submit("LOGIC_ERROR", "made up cause", List.of("LOG-999"), 90, "fix it"),
                AgentDecision.unknown("no real evidence available"));
        JudgmentAgent judge = new JudgmentAgent(new RawJudgeProvider(
                "{\"verdict\":\"supported\",\"reason\":\"should never be reached\"}"), "fake-judge");

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop(agent, judge));

        assertEquals(AgentStopReason.INSUFFICIENT_EVIDENCE, outcome.stopReason());
        assertTrue(agent.requests().stream()
                .anyMatch(request -> request.userPrompt().contains("submission rejected")));
        assertFalse(trace.events().stream().anyMatch(event -> event.eventType().equals("judgment")),
                "rule rejection must happen before any judge call");
    }

    @Test
    void unknownConclusionsSkipTheJudgeEntirely() {
        FakeLLMProvider agent = new FakeLLMProvider(
                AgentDecision.unknown("insufficient evidence"));
        JudgmentAgent judge = new JudgmentAgent(new RawJudgeProvider(new java.io.IOException("never called")),
                "fake-judge");

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop(agent, judge));

        assertEquals(AgentStopReason.INSUFFICIENT_EVIDENCE, outcome.stopReason());
        assertTrue(outcome.diagnosis().judgeVerdict().isBlank());
        assertFalse(trace.events().stream().anyMatch(event -> event.eventType().equals("judgment")));
    }

    @Test
    void noJudgeMeansNoStampAndP0Behavior() {
        FakeLLMProvider agent = new FakeLLMProvider(submit());

        InvestigationAgentLoop.AgentOutcome outcome = runQuietly(loop(agent, null));

        assertEquals(AgentStopReason.COMPLETED, outcome.stopReason());
        assertTrue(outcome.diagnosis().judgeVerdict().isBlank(),
                "pre-judge signature: diagnosis JSON must stay judge-clean when review is off");
        assertFalse(trace.events().stream().anyMatch(event -> event.eventType().equals("judgment")));
    }

    /** Judge-only raw-content provider: judgment responses are plain model text,
     * not agent decisions, so they must bypass the decision parser (see
     * JudgmentAgentTest#decisionParserShapedScriptCannotFakeAJudgment). */
    private static final class RawJudgeProvider extends JudgmentAgentTest.RawProvider {
        RawJudgeProvider(Object... script) {
            for (Object item : script) {
                if (item instanceof Exception failure) {
                    enqueueFailure(failure);
                } else {
                    enqueue(String.valueOf(item));
                }
            }
        }
    }
}
