package com.cifa.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Local contract tests for the POST /api/diagnoses request rules (no server, no DB). */
class SubmissionValidatorTest {

    private final RepoRegistry repos = new RepoRegistry("");

    private SubmissionValidatorTest register() {
        try {
            repos.register("demo-repo", Files.createTempDirectory("validator-repo"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    @Test
    void acceptsWellFormedSubmissionIncludingEmptyLog() {
        register().validate("agent", "demo-repo", "");
        register().validate("baseline", "demo-repo", "some log");
    }

    @Test
    void rejectsBadModeUnknownAliasAndMissingLog() {
        register();
        for (String badMode : Arrays.asList(null, "", "mcp", "AGENT-ONLY")) {
            assertThrows(IllegalArgumentException.class,
                    () -> SubmissionValidator.validate(badMode, "demo-repo", "log", repos),
                    () -> "mode " + badMode + " must be rejected");
        }
        assertThrows(IllegalArgumentException.class,
                () -> SubmissionValidator.validate("agent", "no-such-repo", "log", repos));
        assertThrows(IllegalArgumentException.class,
                () -> SubmissionValidator.validate("agent", "demo-repo", null, repos),
                "null log is missing, not empty");
        // a client-supplied server path is NOT how repos are chosen; alias only
        assertThrows(IllegalArgumentException.class,
                () -> SubmissionValidator.validate("agent", "/etc/passwd", "log", repos));
    }

    @Test
    void oversizedLogIsADistinctTooLargeCase() {
        register();
        String huge = "x".repeat(SubmissionValidator.MAX_LOG_CHARS + 1);
        assertThrows(SubmissionValidator.LogTooLargeException.class,
                () -> SubmissionValidator.validate("agent", "demo-repo", huge, repos));
        // exactly at the cap is fine
        String atCap = "x".repeat(SubmissionValidator.MAX_LOG_CHARS);
        assertDoesNotThrow(() -> SubmissionValidator.validate("agent", "demo-repo", atCap, repos));
    }

    private void validate(String mode, String repo, String log) {
        SubmissionValidator.validate(mode, repo, log, repos);
    }
}
