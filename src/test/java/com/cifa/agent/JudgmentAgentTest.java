package com.cifa.agent;

import com.cifa.evidence.Evidence;
import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.llm.AnalysisResult;
import com.cifa.llm.FakeLLMProvider;
import com.cifa.llm.LLMProvider;
import com.cifa.llm.LLMRequest;
import com.cifa.llm.LLMResponse;
import com.cifa.context.AnalysisContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class JudgmentAgentTest {

    /** Judge responses are raw model text, NOT agent decisions — this provider
     * returns strings verbatim so scripts never pass through the decision
     * parser (FakeLLMProvider.enqueueRaw would try to read them as decisions).
     * Package-private: the loop integration test extends it. */
    static class RawProvider implements LLMProvider {
        private final Queue<Object> script = new ArrayDeque<>();
        final List<LLMRequest> requests = new ArrayList<>();

        RawProvider enqueue(String rawContent) {
            script.add(rawContent);
            return this;
        }

        RawProvider enqueueFailure(Exception failure) {
            script.add(failure);
            return this;
        }

        @Override
        public LLMResponse invoke(LLMRequest request) throws IOException, InterruptedException {
            requests.add(request);
            Object next = script.poll();
            if (next instanceof IOException ioException) {
                throw ioException;
            }
            if (next instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            String content = next == null ? "" : next.toString();
            return new LLMResponse(content, null, 1, 1, 0);
        }

        @Override
        public AnalysisResult analyze(AnalysisContext context) {
            throw new UnsupportedOperationException("judge never uses the baseline path");
        }
    }

    private Evidence logEvidence() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        return catalog.register(EvidenceType.LOG, "normalized_log:1-2",
                "java.lang.NullPointerException at UserService.java:9", "preprocessing", 1, 2, "");
    }

    private static JudgmentAgent.Judgment review(LLMProvider provider, List<Evidence> cited)
            throws InterruptedException {
        return new JudgmentAgent(provider, "fake-judge")
                .review("LOGIC_ERROR", "null check missing in UserService", cited);
    }

    @Test
    void supportedVerdictParsesAndPromptCarriesTheSubmission() throws InterruptedException {
        RawProvider provider = new RawProvider()
                .enqueue("{\"verdict\":\"supported\",\"reason\":\"LOG-001 shows the NPE line\"}");

        JudgmentAgent.Judgment judgment = review(provider, List.of(logEvidence()));

        assertEquals(JudgmentAgent.Verdict.SUPPORTED, judgment.verdict());
        assertEquals("LOG-001 shows the NPE line", judgment.reason());
        String systemPrompt = provider.requests.get(0).systemPrompt();
        assertTrue(systemPrompt.contains("independent semantic reviewer"), systemPrompt);
        assertTrue(systemPrompt.contains("\"verdict\":\"unsupported\""), systemPrompt);
        assertTrue(systemPrompt.contains("EVIDENCE ONLY"), systemPrompt);
        String userPrompt = provider.requests.get(0).userPrompt();
        assertTrue(userPrompt.contains("category: LOGIC_ERROR"), userPrompt);
        assertTrue(userPrompt.contains("null check missing in UserService"), userPrompt);
        assertTrue(userPrompt.contains("LOG-001"), userPrompt);
        assertTrue(userPrompt.contains("NullPointerException at UserService.java:9"), userPrompt);
    }

    @Test
    void unsupportedVerdictParsesWhenItCarriesAReason() throws InterruptedException {
        RawProvider provider = new RawProvider()
                .enqueue("{\"verdict\":\"unsupported\",\"reason\":\"LOG-001 shows the trace, not the assignment\"}");

        JudgmentAgent.Judgment judgment = review(provider, List.of(logEvidence()));

        assertEquals(JudgmentAgent.Verdict.UNSUPPORTED, judgment.verdict());
        assertFalse(judgment.reason().isBlank());
    }

    @Test
    void unsupportedWithoutAReasonIsAJudgeMalfunctionNotARejection() throws InterruptedException {
        // A rejection without a named gap cannot drive a revision — blocking on
        // it would make the gate unactionable, so it degrades to UNAVAILABLE.
        RawProvider blank = new RawProvider().enqueue("{\"verdict\":\"unsupported\",\"reason\":\"\"}");
        assertEquals(JudgmentAgent.Verdict.UNAVAILABLE, review(blank, List.of(logEvidence())).verdict());

        RawProvider missing = new RawProvider().enqueue("{\"verdict\":\"unsupported\"}");
        JudgmentAgent.Judgment judgment = review(missing, List.of(logEvidence()));
        assertEquals(JudgmentAgent.Verdict.UNAVAILABLE, judgment.verdict());
        assertTrue(judgment.reason().contains("without a reason"));
    }

    @Test
    void inconclusiveVerdictParses() throws InterruptedException {
        RawProvider provider = new RawProvider()
                .enqueue("{\"verdict\":\"inconclusive\",\"reason\":\"evidence is ambiguous\"}");

        JudgmentAgent.Judgment judgment = review(provider, List.of(logEvidence()));

        assertEquals(JudgmentAgent.Verdict.INCONCLUSIVE, judgment.verdict());
        assertEquals("evidence is ambiguous", judgment.reason());
    }

    @Test
    void garbageVerdictValueIsUnavailableNeverAnImplicitBlockOrPass() throws InterruptedException {
        RawProvider provider = new RawProvider().enqueue("{\"verdict\":\"probably\",\"reason\":\"guessing\"}");

        JudgmentAgent.Judgment judgment = review(provider, List.of(logEvidence()));

        assertEquals(JudgmentAgent.Verdict.UNAVAILABLE, judgment.verdict());
        assertTrue(judgment.reason().contains("unknown judgment verdict"));
    }

    @Test
    void unparseableOutputIsUnavailable() throws InterruptedException {
        RawProvider provider = new RawProvider().enqueue("I think the evidence supports it.");

        JudgmentAgent.Judgment judgment = review(provider, List.of(logEvidence()));

        assertEquals(JudgmentAgent.Verdict.UNAVAILABLE, judgment.verdict());
        assertTrue(judgment.reason().contains("unparseable"));
    }

    @Test
    void transportFailureIsUnavailable() throws InterruptedException {
        RawProvider provider = new RawProvider().enqueueFailure(new IOException("judge api down"));

        JudgmentAgent.Judgment judgment = review(provider, List.of(logEvidence()));

        assertEquals(JudgmentAgent.Verdict.UNAVAILABLE, judgment.verdict());
        assertTrue(judgment.reason().contains("judge call failed"));
        assertTrue(judgment.reason().contains("judge api down"));
    }

    @Test
    void cancellationPropagatesInsteadOfBecomingAVerdict() {
        RawProvider provider = new RawProvider().enqueueFailure(new InterruptedException("cancelled"));

        assertThrows(InterruptedException.class, () -> review(provider, List.of(logEvidence())));
    }

    @Test
    void noCitedEvidenceShortCircuitsWithoutACall() throws InterruptedException {
        RawProvider provider = new RawProvider().enqueue("{\"verdict\":\"supported\"}");

        JudgmentAgent.Judgment judgment = review(provider, List.of());

        assertEquals(JudgmentAgent.Verdict.UNAVAILABLE, judgment.verdict());
        assertEquals(0, provider.requests.size());
    }

    @Test
    void longEvidenceContentIsTruncatedPerItem() throws InterruptedException {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        Evidence big = catalog.register(EvidenceType.LOG, "normalized_log:1-2", "x".repeat(5000),
                "preprocessing", 1, 2, "");
        RawProvider provider = new RawProvider().enqueue("{\"verdict\":\"supported\"}");

        review(provider, List.of(big));

        String userPrompt = provider.requests.get(0).userPrompt();
        assertTrue(userPrompt.contains("...[truncated]"), () -> userPrompt);
        assertFalse(userPrompt.contains("x".repeat(2001)), () -> userPrompt);
    }

    @Test
    void evidenceBlockRespectsTheTotalReviewBudget() throws InterruptedException {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        List<Evidence> cited = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cited.add(catalog.register(EvidenceType.LOG, "normalized_log:" + (i + 1) + "-" + (i + 2),
                    "y".repeat(1999), "preprocessing", i + 1, i + 2, ""));
        }
        RawProvider provider = new RawProvider().enqueue("{\"verdict\":\"supported\"}");

        review(provider, cited);

        String userPrompt = provider.requests.get(0).userPrompt();
        assertTrue(userPrompt.contains("more evidence items omitted"), () -> userPrompt);
    }

    @Test
    void fakeProviderServesVerbatimContentForJudgeScripts() throws InterruptedException {
        // enqueueRaw content reaches the judge verbatim — the judge parses the
        // raw text, not the decision-parse result. So a single FakeLLMProvider
        // script can interleave agent decisions and judgment payloads, which is
        // what the eval --judge A/B path relies on. (Caveat pinned here: the
        // payload must not carry decision keys like type/category that would
        // trip AgentDecision.fromJson at invoke time.)
        FakeLLMProvider provider = new FakeLLMProvider();
        provider.enqueueRaw("{\"verdict\":\"supported\",\"reason\":\"ok\"}");
        JudgmentAgent.Judgment judgment = new JudgmentAgent(provider, "fake-judge")
                .review("LOGIC_ERROR", "root cause", List.of(logEvidence()));
        assertEquals(JudgmentAgent.Verdict.SUPPORTED, judgment.verdict());
    }
}
