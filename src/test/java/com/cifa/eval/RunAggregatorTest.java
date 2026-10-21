package com.cifa.eval;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * N=3 aggregation math on hand-built reports: mean / population stddev per
 * case and per category, plus the one-corpus guard.
 */
class RunAggregatorTest {

    private static EvalResult result(String caseId, String category, boolean categoryCorrect,
                                     int tokens, long elapsedMillis) {
        return new EvalResult(caseId, category, category, "accept", categoryCorrect,
                true, true, true, true, true, true,
                List.of(), true, 0, 0, tokens, elapsedMillis, "COMPLETED", "");
    }

    private static EvalReport run(int passTokenShift) {
        return new EvalReport("agent", "p0-v1", Instant.EPOCH, List.of(
                result("case-a", "LOGIC_ERROR", true, 100 + passTokenShift, 10 + passTokenShift),
                result("case-b", "LOGIC_ERROR", true, 200, 20),
                // categoryCorrect flips across runs: accuracy must average 2/3, stddev > 0
                result("case-c", "DEPENDENCY_BUILD", passTokenShift != 2, 300, 30)));
    }

    @Test
    void aggregatesThreeRunsPerCaseAndPerCategory() {
        RunAggregator aggregator = new RunAggregator(List.of(run(0), run(1), run(2)));

        assertEquals(3, aggregator.runCount());

        RunAggregator.CaseStats caseA = aggregator.perCase().get(0);
        assertEquals("case-a", caseA.caseId());
        assertEquals(101.0, caseA.meanTokens(), 1e-9);          // 100,101,102
        assertEquals(Math.sqrt((1.0 + 0.0 + 1.0) / 3), caseA.tokenStddev(), 1e-9);
        assertEquals(11.0, caseA.meanElapsedMillis(), 1e-9);
        assertEquals(Math.sqrt(2.0 / 3), caseA.elapsedStddev(), 1e-9);
        assertEquals(3, caseA.passCount());

        // case-c is categoryCorrect in runs 1,2 and wrong in run 3: passes 2/3.
        RunAggregator.CaseStats caseC = aggregator.perCase().get(2);
        assertEquals(2, caseC.passCount());
        assertEquals(300.0, caseC.meanTokens(), 1e-9);

        RunAggregator.CategoryStats logic = aggregator.perCategory().get(0);
        assertEquals("LOGIC_ERROR", logic.expectedCategory());
        assertEquals(2, logic.caseCount());
        assertEquals(1.0, logic.meanCategoryAccuracy(), 1e-9);
        assertEquals(0.0, logic.categoryAccuracyStddev(), 1e-9);
        // per-run tokens/case = 150, 150.5, 151 → mean 150.5, pop sd sqrt((0.25+0+0.25)/3)
        assertEquals(150.5, logic.meanTokensPerCase(), 1e-9);
        assertEquals(Math.sqrt(1.0 / 6), logic.tokensPerCaseStddev(), 1e-9);

        RunAggregator.CategoryStats dependency = aggregator.perCategory().get(1);
        assertEquals("DEPENDENCY_BUILD", dependency.expectedCategory());
        // accuracies per run: 1, 1, 0 → mean 2/3, deviations ±1/3, -2/3 → sd sqrt(2/9)
        assertEquals(2.0 / 3, dependency.meanCategoryAccuracy(), 1e-9);
        assertEquals(Math.sqrt(2.0 / 9), dependency.categoryAccuracyStddev(), 1e-9);
    }

    @Test
    void rendersTablesForRunsAndCases() {
        String markdown = new RunAggregator(List.of(run(0), run(1), run(2))).renderMarkdown();

        assertTrue(markdown.contains("## Run aggregation (--runs 3)"));
        assertTrue(markdown.contains("| LOGIC_ERROR | 2 |"));
        assertTrue(markdown.contains("| case-a |"));
        assertTrue(markdown.contains("| 3/3 |"));
    }

    @Test
    void rejectsRunsOverDifferentCaseSets() {
        EvalReport full = run(0);
        EvalReport trimmed = new EvalReport("agent", "p0-v1", Instant.EPOCH, full.results().subList(0, 2));

        assertThrows(IllegalArgumentException.class, () -> new RunAggregator(List.of(full, trimmed)));
        assertThrows(IllegalArgumentException.class, () -> new RunAggregator(List.of()));
    }
}
