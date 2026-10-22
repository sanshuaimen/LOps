package com.cifa.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3 tasks 1-2: the "错题本" store (JSON roundtrip, corruption fallback,
 * leakage-guarded appends) and the hand-rolled BM25 retrieval (same-family
 * hits, off-family misses, self-case exclusion).
 */
class FailureMemoryTest {

    @TempDir
    Path temp;

    private static FailureMemoryEntry entry(String id, String caseId, String layer, String signature,
                                            String exceptionType, String category) {
        return new FailureMemoryEntry(id, caseId, layer, signature, exceptionType,
                List.of(exceptionType.toLowerCase(), "checkout"), category,
                "root cause summary of " + caseId,
                List.of("Src.java:12"), "guard the null path", "golden-confirmed");
    }

    @Test
    void storeRoundtripsEntriesAcrossReload() throws Exception {
        Path file = temp.resolve("failure-memory.json");
        FailureMemoryStore store = new FailureMemoryStore(file);
        store.append(entry("m-1", "multi-module-npe", "regression",
                "NullPointerException customerType is null", "NullPointerException", "LOGIC_ERROR"));
        store.append(entry("m-2", "gradle-compile-symbol", "robustness",
                "cannot find symbol CartTotals", "CompilationError", "DEPENDENCY_BUILD"));

        FailureMemoryStore reloaded = new FailureMemoryStore(file);
        assertEquals(2, reloaded.size());
        assertEquals("multi-module-npe", reloaded.byId("m-1").orElseThrow().caseId());
        assertEquals("golden-confirmed", reloaded.byId("m-2").orElseThrow().source());
    }

    @Test
    void corruptedMemoryFileDegradesToEmptyNotCrash() throws Exception {
        Path file = temp.resolve("broken.json");
        Files.writeString(file, "{not json at all");

        assertEquals(0, new FailureMemoryStore(file).size());
    }

    @Test
    void acceptLayerMayNeverSeedTheNotebook() {
        FailureMemoryStore store = new FailureMemoryStore(temp.resolve("fm.json"));

        IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
                () -> store.append(entry("m-x", "git-regression-checkout", "accept",
                        "behavior changed", "IllegalStateException", "LOGIC_ERROR")));
        assertTrue(blocked.getMessage().contains("accept"), blocked.getMessage());
    }

    @Test
    void duplicateIdsAreRejected() throws Exception {
        FailureMemoryStore store = new FailureMemoryStore(temp.resolve("fm.json"));
        store.append(entry("m-1", "multi-module-npe", "regression", "sig", "NullPointerException", "LOGIC_ERROR"));
        assertThrows(IllegalArgumentException.class,
                () -> store.append(entry("m-1", "other-case", "regression", "sig", "IOException", "INFRA_FLAKY")));
    }

    @Test
    void bm25RetrievesSameFamilyAndSkipsOffFamilyQueries() throws Exception {
        FailureMemoryStore store = new FailureMemoryStore(temp.resolve("fm.json"));
        store.append(entry("m-1", "multi-module-npe", "regression",
                "java.lang.NullPointerException customerType is null at PricingService", "NullPointerException",
                "LOGIC_ERROR"));
        store.append(entry("m-2", "infra-dns-unresolved", "robustness",
                "UnknownHostException cannot resolve registry host", "UnknownHostException", "INFRA_FLAKY"));

        FailureMemoryRetrieval retrieval = new FailureMemoryRetrieval(store.entries());

        List<FailureMemoryEntry> npeHits = retrieval.retrieve(
                "java.lang.NullPointerException while applying discount", "fresh-case-1", 2, 0.5);
        assertEquals("multi-module-npe", npeHits.get(0).caseId(), "same-family must rank first");

        List<FailureMemoryEntry> dnsHits = retrieval.retrieve(
                "UnknownHostException cannot resolve artifact repo host", "fresh-case-1", 2, 0.5);
        assertEquals("infra-dns-unresolved", dnsHits.get(0).caseId());

        List<FailureMemoryEntry> unrelated = retrieval.retrieve(
                "password rejected for database user admin", "fresh-case-1", 2, 0.5);
        assertTrue(unrelated.isEmpty(), "off-family query must fall below the threshold");
    }

    @Test
    void retrievalNeverReturnsTheQueryingCaseItself() throws Exception {
        // Leakage protocol: a case must not retrieve its own notebook page.
        FailureMemoryStore store = new FailureMemoryStore(temp.resolve("fm.json"));
        store.append(entry("m-1", "multi-module-npe", "regression",
                "java.lang.NullPointerException customerType is null", "NullPointerException", "LOGIC_ERROR"));

        FailureMemoryRetrieval retrieval = new FailureMemoryRetrieval(store.entries());

        List<FailureMemoryEntry> hits = retrieval.retrieve(
                "java.lang.NullPointerException customerType is null", "multi-module-npe", 2, 0.0);
        assertTrue(hits.isEmpty(), "self-case retrieval must be excluded even at minScore 0");
    }

    @Test
    void emptyStoreAndZeroKRetrieveNothing() {
        FailureMemoryRetrieval retrieval = new FailureMemoryRetrieval(List.of());
        assertTrue(retrieval.retrieve("anything", "case", 2, 0.0).isEmpty());
        assertTrue(new FailureMemoryRetrieval(List.of(
                entry("m-1", "c", "regression", "sig", "NullPointerException", "LOGIC_ERROR")))
                .retrieve("sig", "case", 0, 0.0).isEmpty());
    }
}
