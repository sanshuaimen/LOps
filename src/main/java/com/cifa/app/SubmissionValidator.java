package com.cifa.app;

import java.util.Set;

/**
 * Pure request validation for POST /api/diagnoses (P1 plan, task 6 contract).
 * Deliberately framework-free so the rules are unit-testable without a server.
 */
public final class SubmissionValidator {

    /** P1 contract: log content travels in the request body; default cap 2 MB of chars. */
    public static final int MAX_LOG_CHARS = 2_000_000;
    private static final Set<String> MODES = Set.of("agent", "baseline");

    private SubmissionValidator() {
    }

    /** @throws IllegalArgumentException with a client-readable message on any violation. */
    public static void validate(String mode, String repoAlias, String logContent, RepoRegistry repos) {
        if (mode == null || !MODES.contains(mode)) {
            throw new IllegalArgumentException("mode must be 'agent' or 'baseline'");
        }
        if (!repos.knows(repoAlias)) {
            throw new IllegalArgumentException("unknown repo alias: " + repoAlias);
        }
        if (logContent == null) {
            throw new IllegalArgumentException("log content is required (empty string is allowed)");
        }
        if (logContent.length() > MAX_LOG_CHARS) {
            throw new LogTooLargeException("log content exceeds " + MAX_LOG_CHARS + " chars");
        }
    }

    /** Distinct type so the controller can answer 413 instead of 400. */
    public static final class LogTooLargeException extends IllegalArgumentException {
        public LogTooLargeException(String message) {
            super(message);
        }
    }
}
