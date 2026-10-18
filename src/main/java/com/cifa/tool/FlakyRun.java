package com.cifa.tool;

/**
 * One recorded run of a test. {@code rerunPassed} marks an immediate retry
 * after a failure; {@code relatedCommit} is empty when no code change could be
 * associated with the run.
 */
public record FlakyRun(String buildId, boolean passed, boolean rerunPassed, String relatedCommit) {
    public FlakyRun {
        buildId = buildId == null ? "" : buildId;
        relatedCommit = relatedCommit == null ? "" : relatedCommit;
    }

    public boolean hasUnrelatedFailure() {
        return !passed && relatedCommit.isBlank();
    }
}
