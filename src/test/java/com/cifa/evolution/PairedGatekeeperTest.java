package com.cifa.evolution;

import com.cifa.eval.EvalReport;
import com.cifa.eval.EvalResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The paired gate's contract: zero-regression is a HARD constraint (mean-up-
 * but-broke-solved-cases must reject), wins must reach the threshold, the
 * aggregate pass-rate is display-only, and mismatched case sets are invalid
 * rather than silently gated.
 */
class PairedGatekeeperTest {

    private static EvalResult result(String caseId, boolean passed) {
        return new EvalResult(caseId, "DEPENDENCY_BUILD", passed ? "DEPENDENCY_BUILD" : "LOGIC_ERROR",
                "regression", passed, passed, passed, passed,
                true, true, true, List.of(), false, 0, 0, 10, 1,
                passed ? "COMPLETED" : "MAX_ROUNDS", "");
    }

    private static EvalReport report(String subject, List<EvalResult> results) {
        return new EvalReport(subject, "test", Instant.EPOCH, results);
    }

    @Test
    void strictImprovementPromotes() {
        EvalReport baseline = report("baseline", List.of(
                result("a", true), result("b", false), result("c", true)));
        EvalReport candidate = report("candidate", List.of(
                result("a", true), result("b", true), result("c", true)));

        PairedGatekeeper.GateVerdict verdict = PairedGatekeeper.gate(baseline, candidate);
        assertTrue(verdict.promote());
        assertEquals(1, verdict.wins());
        assertEquals(0, verdict.losses());
        assertEquals(2, verdict.bothPass());
        assertEquals(0, verdict.bothFail());
        assertTrue(verdict.reason().contains("strictly positive"));
        assertEquals(List.of("b"), verdict.improvementCases());
    }

    @Test
    void meanUpButBrokeSolvedCaseRejects() {
        // baseline 50% → candidate 66%: the AGGREGATE improved, but case "c" (previously
        // solved) regressed. The plan's classic trap — the gate must reject.
        EvalReport baseline = report("baseline", List.of(
                result("a", false), result("b", true), result("c", true), result("d", false)));
        EvalReport candidate = report("candidate", List.of(
                result("a", true), result("b", true), result("c", false), result("d", true)));

        PairedGatekeeper.GateVerdict verdict = PairedGatekeeper.gate(baseline, candidate);
        assertFalse(verdict.promote());
        assertEquals(2, verdict.wins());
        assertEquals(1, verdict.losses());
        assertTrue(verdict.candidatePassRate() > verdict.baselinePassRate());
        assertTrue(verdict.reason().contains("zero-regression"), verdict.reason());
        assertEquals(List.of("c"), verdict.regressionCases());
    }

    @Test
    void behaviorChangeWithoutWinsRejects() {
        EvalReport baseline = report("baseline", List.of(result("a", true), result("b", true)));
        EvalReport candidate = report("candidate", List.of(result("a", true), result("b", true)));

        PairedGatekeeper.GateVerdict verdict = PairedGatekeeper.gate(baseline, candidate);
        assertFalse(verdict.promote());
        assertTrue(verdict.reason().contains("below the required"), verdict.reason());
    }

    @Test
    void minWinsIsConfigurable() {
        EvalReport baseline = report("baseline", List.of(
                result("a", false), result("b", false), result("c", true), result("d", true)));
        EvalReport candidate = report("candidate", List.of(
                result("a", true), result("b", false), result("c", true), result("d", true)));

        assertTrue(PairedGatekeeper.gate(baseline, candidate, 1).promote());
        PairedGatekeeper.GateVerdict strict = PairedGatekeeper.gate(baseline, candidate, 2);
        assertFalse(strict.promote());
        assertTrue(strict.reason().contains("below the required 2"));
        assertThrows(IllegalArgumentException.class, () -> PairedGatekeeper.gate(baseline, candidate, 0));
    }

    @Test
    void mismatchedCaseSetsAreInvalidNotApproximate() {
        EvalReport baseline = report("baseline", List.of(result("a", true), result("b", true)));
        EvalReport candidate = report("candidate", List.of(result("a", true), result("x", true)));

        IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
                () -> PairedGatekeeper.gate(baseline, candidate));
        assertTrue(blocked.getMessage().contains("identical case sets"));
    }

    @Test
    void reportsRoundTripThroughJsonForTheGateCommand() {
        EvalReport original = report("agent", List.of(
                result("compile-symbol", true), result("flaky-retry", false)));
        EvalReport reloaded = EvalReport.fromJson(original.toJson());

        assertEquals(original.subject(), reloaded.subject());
        assertEquals(original.passedCount(), reloaded.passedCount());
        assertEquals(original.results().size(), reloaded.results().size());
        assertEquals(original.results().get(1).caseId(), reloaded.results().get(1).caseId());
        // the gate itself accepts reloaded reports (this is its real input path)
        PairedGatekeeper.GateVerdict verdict = PairedGatekeeper.gate(reloaded, reloaded);
        assertFalse(verdict.promote()); // zero wins
        assertEquals(1, verdict.bothPass());  // compile-symbol solved on both sides
        assertEquals(1, verdict.bothFail());  // flaky-retry failed on both sides
    }
}
