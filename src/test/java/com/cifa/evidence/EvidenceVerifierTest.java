package com.cifa.evidence;

import com.cifa.security.RepositoryPathPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceVerifierTest {

    @TempDir
    Path repo;

    private EvidenceCatalog catalog;
    private EvidenceVerifier verifier;

    private EvidenceVerifier setup() {
        catalog = new EvidenceCatalog("task-1");
        return new EvidenceVerifier(catalog, new RepositoryPathPolicy(repo));
    }

    @Test
    void rejectsFabricatedEvidenceIds() {
        verifier = setup();
        catalog.register(EvidenceType.LOG, "normalized_log:1-3", "error", "failure_evidence", 1, 3, "");

        List<EvidenceVerifier.Violation> violations = verifier.verifyCitations(List.of("LOG-999"));

        assertEquals(1, violations.size());
        assertEquals("UNKNOWN_EVIDENCE", violations.get(0).code());
    }

    @Test
    void rejectsMalformedAndEmptyCitations() {
        verifier = setup();

        assertEquals("MALFORMED_ID",
                verifier.verifyCitations(List.of("i-am-not-an-id")).get(0).code());
        assertEquals("NO_EVIDENCE",
                verifier.verifyCitations(List.of()).get(0).code());
        assertEquals("MALFORMED_ID",
                verifier.verifyCitations(List.of("log-1")).get(0).code());
    }

    @Test
    void acceptsWellFormedCitationsAcrossTypes() {
        verifier = setup();
        Evidence log = catalog.register(EvidenceType.LOG, "normalized_log:1-3", "error", "failure_evidence", 1, 3, "");
        Evidence source = catalog.register(EvidenceType.SOURCE, "src/A.java:9-12", "code", "source_location", 9, 12, "");
        Evidence git = catalog.register(EvidenceType.GIT, "log:src/A.java@abc1234", "commits", "git_history",
                Evidence.NO_LINE, Evidence.NO_LINE, "abc1234def5678");
        Evidence test = catalog.register(EvidenceType.TEST_REPORT, "surefire-reports", "failing", "surefire_report");
        Evidence flaky = catalog.register(EvidenceType.FLAKY_HISTORY, "flaky-history:T.t", "runs", "flaky_history");

        List<EvidenceVerifier.Violation> violations =
                verifier.verifyCitations(List.of(log.id(), source.id(), git.id(), test.id(), flaky.id()));

        assertTrue(violations.isEmpty(), () -> violations.toString());
    }

    @Test
    void flagsLogEvidenceWithoutRealRange() {
        verifier = setup();
        // The catalog allows registration; shape rules are the verifier's job.
        Evidence rangeless = catalog.register(EvidenceType.LOG, "normalized_log", "content",
                "failure_evidence", Evidence.NO_LINE, Evidence.NO_LINE, "");

        List<EvidenceVerifier.Violation> flagged = verifier.verifyCitations(List.of(rangeless.id()));
        assertEquals(1, flagged.size());
        assertEquals("MISSING_LOG_RANGE", flagged.get(0).code());
    }

    @Test
    void flagsSourceEvidenceWithEscapingPath() throws Exception {
        verifier = setup();
        Files.writeString(repo.resolve("A.java"), "code");
        Evidence good = catalog.register(EvidenceType.SOURCE, "A.java:1-2", "code", "source_location", 1, 2, "");
        Evidence escapes = catalog.register(EvidenceType.SOURCE, "../outside/A.java:1-2", "code",
                "source_location", 1, 2, "");

        assertEquals(0, verifier.verifyCitations(List.of(good.id())).size());
        List<EvidenceVerifier.Violation> violations = verifier.verifyCitations(List.of(escapes.id()));
        assertEquals(1, violations.size());
        assertEquals("SOURCE_PATH_ESCAPES", violations.get(0).code());
    }

    @Test
    void flagsGitEvidenceWithoutCommit() {
        verifier = setup();
        Evidence bad = catalog.register(EvidenceType.GIT, "log:A.java@", "output", "git_history",
                Evidence.NO_LINE, Evidence.NO_LINE, "");

        List<EvidenceVerifier.Violation> violations = verifier.verifyCitations(List.of(bad.id()));
        assertEquals(1, violations.size());
        assertEquals("MISSING_COMMIT", violations.get(0).code());
    }

    @Test
    void integritySweepPassesForCleanCatalog() {
        verifier = setup();
        catalog.register(EvidenceType.LOG, "normalized_log:1-3", "error", "failure_evidence", 1, 3, "");
        catalog.register(EvidenceType.GIT, "log:A.java@abc1234", "commits", "git_history",
                Evidence.NO_LINE, Evidence.NO_LINE, "abc1234def5678");

        assertTrue(verifier.verifyIntegrity().isEmpty());
    }

    @Test
    void hashCheckDetectsTamperedQuotedContent() {
        Evidence evidence = new Evidence("LOG-001", EvidenceType.LOG, "normalized_log:1-2", "real content",
                Evidence.sha256("real content"), "failure_evidence", 1, 2, "");

        assertTrue(evidence.contentMatches("real content"));
        assertFalse(evidence.contentMatches("fabricated content"));
    }
}
