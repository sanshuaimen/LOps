package com.cifa.eval;

import com.cifa.classification.FailureCategory;
import com.cifa.context.AnalysisContext;
import com.cifa.llm.AnalysisResult;
import com.cifa.llm.LLMProvider;
import com.cifa.llm.LLMRequest;
import com.cifa.llm.LLMResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

/** Auxiliary grading discipline: a keyword hit never reaches the judge; the
 * judge can only rescue a miss; any judge malfunction keeps the deterministic
 * keyword verdict. */
class EvalJudgeGradingTest {

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

    private static final JudgeGrader grader(RawProvider provider) {
        return new JudgeGrader(provider, "fake-grader");
    }

    private static final GoldenCase goldenCase() {
        return new GoldenCase("case-1", Path.of("build.log"), Path.of("repo"),
                FailureCategory.LOGIC_ERROR, "accept",
                List.of("null check", "UserService"), List.of(), false,
                List.of(), List.of(), List.of());
    }

    private static final EvalObservation observation(String rootCause) {
        return new EvalObservation("LOGIC_ERROR", rootCause, List.of(), "", false,
                List.of(), List.of(), 7, 100, 10, "COMPLETED", "");
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted(); // tests below toggle the interrupt flag; keep the suite clean
    }

    @Test
    void keywordHitPassesWithoutConsultingTheJudge() {
        RawProvider provider = new RawProvider(); // nothing scripted: a judge call would explode the script

        EvalResult result = new EvalRunner().withJudgeGrader(grader(provider))
                .evaluate(goldenCase(), observation("null check missing in UserService"));

        assertTrue(result.rootCauseCorrect());
        assertEquals(0, provider.requests.size());
    }

    @Test
    void keywordMissIsRescuedWhenTheJudgeSaysMatch() {
        RawProvider provider = new RawProvider()
                .enqueue("{\"match\":\"yes\",\"reason\":\"same causal idea\"}");

        EvalResult result = new EvalRunner().withJudgeGrader(grader(provider))
                .evaluate(goldenCase(), observation("missing null guard in the user service"));

        assertTrue(result.rootCauseCorrect(), "differently-worded correct diagnosis must be rescued");
        assertEquals(1, provider.requests.size());
    }

    @Test
    void keywordMissStaysWrongWhenTheJudgeSaysNo() {
        RawProvider provider = new RawProvider()
                .enqueue("{\"match\":\"no\",\"reason\":\"wrong component\"}");

        EvalResult result = new EvalRunner().withJudgeGrader(grader(provider))
                .evaluate(goldenCase(), observation("cache stampede overwhelmed the service"));

        assertFalse(result.rootCauseCorrect());
        assertEquals(1, provider.requests.size());
    }

    @Test
    void judgeMalfunctionKeepsTheDeterministicKeywordVerdict() {
        RawProvider provider = new RawProvider().enqueueFailure(new IOException("grader api down"));

        EvalResult result = new EvalRunner().withJudgeGrader(grader(provider))
                .evaluate(goldenCase(), observation("missing null guard in the user service"));

        assertFalse(result.rootCauseCorrect(), "UNAVAILABLE must fall back to the keyword miss, not rescue it");
    }

    @Test
    void nullGraderKeepsPureDeterministicScoring() {
        EvalResult result = new EvalRunner()
                .evaluate(goldenCase(), observation("missing null guard in the user service"));

        assertFalse(result.rootCauseCorrect());
    }

    @Test
    void cancellationKeepsKeywordVerdictAndRestoresTheInterruptFlag() {
        RawProvider provider = new RawProvider().enqueueFailure(new InterruptedException("cancelled"));

        EvalResult result = new EvalRunner().withJudgeGrader(grader(provider))
                .evaluate(goldenCase(), observation("missing null guard in the user service"));

        assertFalse(result.rootCauseCorrect());
        assertTrue(Thread.currentThread().isInterrupted(),
                "interrupt must be restored, not swallowed");
    }
}
