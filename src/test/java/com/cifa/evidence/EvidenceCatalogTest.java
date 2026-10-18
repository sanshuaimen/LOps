package com.cifa.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceCatalogTest {

    @Test
    void assignsSequentialIdsPerType() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");

        Evidence log1 = catalog.register(EvidenceType.LOG, "build.log:10-20", "NPE at line 12", "failure_evidence");
        Evidence src1 = catalog.register(EvidenceType.SOURCE, "src/UserService.java", "class UserService", "source_location");
        Evidence log2 = catalog.register(EvidenceType.LOG, "build.log:30-40", "AssertionError", "failure_evidence");

        assertEquals("LOG-001", log1.id());
        assertEquals("SRC-001", src1.id());
        assertEquals("LOG-002", log2.id());
        assertEquals(3, catalog.size());
    }

    @Test
    void computesContentHashOfRegisteredContent() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");

        Evidence evidence = catalog.register(EvidenceType.GIT, "abc1234", "fix: null check", "git_history");

        assertEquals(Evidence.sha256("fix: null check"), evidence.contentHash());
        assertTrue(evidence.contentMatches("fix: null check"));
        assertFalse(evidence.contentMatches("fabricated content"));
    }

    @Test
    void findResolvesRegisteredIdAndRejectsFabricatedOnes() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        catalog.register(EvidenceType.LOG, "build.log:1-5", "error line", "failure_evidence");

        assertTrue(catalog.find("LOG-001").isPresent());
        assertTrue(catalog.find(" log-001 ").isPresent());
        assertTrue(catalog.find("LOG-999").isEmpty());
        assertTrue(catalog.find("MADE-UP-ID").isEmpty());
        assertTrue(catalog.find(null).isEmpty());
    }

    @Test
    void evidenceDoesNotLeakAcrossTaskCatalogs() {
        EvidenceCatalog first = new EvidenceCatalog("task-1");
        EvidenceCatalog second = new EvidenceCatalog("task-2");
        first.register(EvidenceType.LOG, "build.log:1-5", "error line", "failure_evidence");

        assertTrue(second.find("LOG-001").isEmpty());
        assertEquals(0, second.size());
    }

    @Test
    void recordsLineRangeAndCommitMetadata() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");

        Evidence log = catalog.register(EvidenceType.LOG, "build.log:10-12", "NPE", "failure_evidence", 10, 12, "");
        Evidence git = catalog.register(EvidenceType.GIT, "src/A.java", "diff --git a/src/A.java", "git_history",
                Evidence.NO_LINE, Evidence.NO_LINE, "abc1234abcdef");

        assertTrue(log.hasLineRange());
        assertEquals(10, log.startLine());
        assertEquals(12, log.endLine());
        assertFalse(log.hasCommit());
        assertTrue(git.hasCommit());
        assertEquals("abc1234abcdef", git.commit());
        assertFalse(git.hasLineRange());
    }

    @Test
    void rejectsRegistrationWithoutToolLocatorOrContent() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");

        assertThrows(IllegalArgumentException.class,
                () -> catalog.register(EvidenceType.LOG, "", "content", "failure_evidence"));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.register(EvidenceType.LOG, "build.log:1", "", "failure_evidence"));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.register(EvidenceType.LOG, "build.log:1", "content", ""));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.register(null, "build.log:1", "content", "failure_evidence"));
        assertEquals(0, catalog.size());
    }

    @Test
    void enforcesCatalogCapacity() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1", 2);
        catalog.register(EvidenceType.LOG, "a", "content-a", "failure_evidence");
        catalog.register(EvidenceType.LOG, "b", "content-b", "failure_evidence");

        assertThrows(IllegalStateException.class,
                () -> catalog.register(EvidenceType.LOG, "c", "content-c", "failure_evidence"));
        assertEquals(2, catalog.size());
    }

    @Test
    void findByLocatorSupportsDuplicateDetection() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        catalog.register(EvidenceType.SOURCE, "src/UserService.java:9", "return null;", "source_location");

        Optional<Evidence> found = catalog.findByLocator(EvidenceType.SOURCE, "src/UserService.java:9");
        assertTrue(found.isPresent());
        assertTrue(catalog.findByLocator(EvidenceType.SOURCE, "src/Other.java:1").isEmpty());
        assertTrue(catalog.findByLocator(EvidenceType.GIT, "src/UserService.java:9").isEmpty());
    }

    @Test
    void byTypeFiltersAndAllPreservesRegistrationOrder() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        catalog.register(EvidenceType.LOG, "log:1", "one", "failure_evidence");
        catalog.register(EvidenceType.SOURCE, "src:1", "two", "source_location");
        catalog.register(EvidenceType.LOG, "log:2", "three", "failure_evidence");

        List<Evidence> logs = catalog.byType(EvidenceType.LOG);
        assertEquals(2, logs.size());
        assertEquals("LOG-001", logs.get(0).id());
        assertEquals("LOG-002", logs.get(1).id());
        assertEquals(List.of("LOG-001", "SRC-001", "LOG-002"),
                catalog.all().stream().map(Evidence::id).toList());
    }
}
