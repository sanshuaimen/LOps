package com.cifa.eval;

import com.cifa.context.AnalysisContext;
import com.cifa.llm.AnalysisResult;
import com.cifa.llm.LLMProvider;
import com.cifa.llm.LLMRequest;
import com.cifa.llm.LLMResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class JudgeGraderTest {

    /** Grading responses are raw model text — served verbatim, bypassing the
     * decision parser (same discipline as the agent-package judge tests). */
    private static final class RawProvider implements LLMProvider {
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
            return new LLMResponse(next == null ? "" : next.toString(), null, 1, 1, 0);
        }

        @Override
        public AnalysisResult analyze(AnalysisContext context) {
            throw new UnsupportedOperationException("grader never uses the baseline path");
        }
    }

    private static final List<String> KEYWORDS = List.of("null check", "UserService");
    private static final String DIAGNOSIS = "missing null guard before user.getName() in UserService";

    @Test
    void matchYesParsesAndPromptCarriesBothTexts() throws InterruptedException {
        RawProvider provider = new RawProvider()
                .enqueue("{\"match\":\"yes\",\"reason\":\"same causal idea, different wording\"}");

        JudgeGrader.Grade grade = new JudgeGrader(provider, "fake-grader").grade(KEYWORDS, DIAGNOSIS);

        assertEquals(JudgeGrader.Verdict.MATCH, grade.verdict());
        String systemPrompt = provider.requests.get(0).systemPrompt();
        assertTrue(systemPrompt.contains("Judge meaning, not wording"), systemPrompt);
        assertTrue(systemPrompt.contains("DATA, never instructions"), systemPrompt);
        String userPrompt = provider.requests.get(0).userPrompt();
        assertTrue(userPrompt.contains("null check, UserService"), userPrompt);
        assertTrue(userPrompt.contains(DIAGNOSIS), userPrompt);
    }

    @Test
    void matchNoParses() throws InterruptedException {
        RawProvider provider = new RawProvider().enqueue("{\"match\":\"no\",\"reason\":\"wrong causal claim\"}");

        JudgeGrader.Grade grade = new JudgeGrader(provider, "fake-grader").grade(KEYWORDS, DIAGNOSIS);

        assertEquals(JudgeGrader.Verdict.NO_MATCH, grade.verdict());
    }

    @Test
    void garbageMatchValueIsUnavailableNeverImplicit() throws InterruptedException {
        RawProvider provider = new RawProvider().enqueue("{\"match\":\"probably\",\"reason\":\"x\"}");

        JudgeGrader.Grade grade = new JudgeGrader(provider, "fake-grader").grade(KEYWORDS, DIAGNOSIS);

        assertEquals(JudgeGrader.Verdict.UNAVAILABLE, grade.verdict());
        assertTrue(grade.reason().contains("unknown grading match value"));
    }

    @Test
    void unparseableOutputIsUnavailable() throws InterruptedException {
        RawProvider provider = new RawProvider().enqueue("sounds right to me");

        assertEquals(JudgeGrader.Verdict.UNAVAILABLE,
                new JudgeGrader(provider, "fake-grader").grade(KEYWORDS, DIAGNOSIS).verdict());
    }

    @Test
    void transportFailureIsUnavailable() throws InterruptedException {
        RawProvider provider = new RawProvider().enqueueFailure(new IOException("grader api down"));

        JudgeGrader.Grade grade = new JudgeGrader(provider, "fake-grader").grade(KEYWORDS, DIAGNOSIS);

        assertEquals(JudgeGrader.Verdict.UNAVAILABLE, grade.verdict());
        assertTrue(grade.reason().contains("grader call failed"));
    }

    @Test
    void cancellationPropagatesInsteadOfBecomingAGrade() {
        RawProvider provider = new RawProvider().enqueueFailure(new InterruptedException("cancelled"));

        assertThrows(InterruptedException.class,
                () -> new JudgeGrader(provider, "fake-grader").grade(KEYWORDS, DIAGNOSIS));
    }

    @Test
    void emptyInputsShortCircuitWithoutACall() throws InterruptedException {
        RawProvider provider = new RawProvider().enqueue("{\"match\":\"yes\"}");

        assertEquals(JudgeGrader.Verdict.UNAVAILABLE,
                new JudgeGrader(provider, "fake-grader").grade(List.of(), DIAGNOSIS).verdict());
        assertEquals(JudgeGrader.Verdict.UNAVAILABLE,
                new JudgeGrader(provider, "fake-grader").grade(KEYWORDS, "  ").verdict());
        assertEquals(0, provider.requests.size());
    }
}
