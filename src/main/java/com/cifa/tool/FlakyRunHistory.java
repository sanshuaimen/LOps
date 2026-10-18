package com.cifa.tool;

import java.util.List;

public record FlakyRunHistory(String testName, List<FlakyRun> runs) {
    public FlakyRunHistory {
        testName = testName == null ? "" : testName;
        runs = List.copyOf(runs);
    }

    public long passCount() {
        return runs.stream().filter(FlakyRun::passed).count();
    }

    public long failCount() {
        return runs.size() - passCount();
    }

    public long unrelatedFailureCount() {
        return runs.stream().filter(FlakyRun::hasUnrelatedFailure).count();
    }

    public long rerunRecoveryCount() {
        return runs.stream().filter(run -> !run.passed() && run.rerunPassed()).count();
    }

    /**
     * Deterministic P0 flaky heuristic: evidence of flakiness is either a
     * failure with no related code change or a failed run that passed on
     * immediate rerun. Runs with a related commit are real regressions, not
     * flakiness.
     */
    public boolean flakyEvidence() {
        return unrelatedFailureCount() > 0 || rerunRecoveryCount() > 0;
    }
}
