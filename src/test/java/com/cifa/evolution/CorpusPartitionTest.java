package com.cifa.evolution;

import com.cifa.classification.FailureCategory;
import com.cifa.eval.GoldenCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The corpus partition's contract: deterministic (same seed → same split on
 * any rerun), stratified per layer, holdout double-blind enforcement fires
 * loudly, smoke tier is a holdout-free subset, and the file round-trips.
 */
class CorpusPartitionTest {

    @TempDir
    Path temp;

    private static GoldenCase golden(String id, String layer) {
        return new GoldenCase(id, Path.of("log"), Path.of("repo"), FailureCategory.DEPENDENCY_BUILD,
                layer, List.of(), List.of(), false, List.of(), List.of(), List.of());
    }

    private static List<GoldenCase> corpus() {
        List<GoldenCase> cases = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            cases.add(golden(String.format("accept-%02d", i), "accept"));
        }
        for (int i = 1; i <= 12; i++) {
            cases.add(golden(String.format("regression-%02d", i), "regression"));
        }
        for (int i = 1; i <= 8; i++) {
            cases.add(golden(String.format("robustness-%02d", i), "robustness"));
        }
        cases.add(golden("security-01", "security"));
        cases.add(golden("security-02", "security"));
        return cases;
    }

    @Test
    void splitIsDeterministicAndStratified() {
        CorpusPartition first = CorpusPartition.build(corpus(), 0.30, CorpusPartition.DEFAULT_SEED);
        CorpusPartition second = CorpusPartition.build(corpus(), 0.30, CorpusPartition.DEFAULT_SEED);

        assertEquals(first.holdoutIds(), second.holdoutIds());
        assertEquals(first.proposalIds(), second.proposalIds());
        assertEquals(34, first.holdoutIds().size() + first.proposalIds().size());

        // every layer is represented in BOTH sides unless it is too tiny to allow it
        assertTrue(first.holdoutIds().stream().anyMatch(id -> id.startsWith("accept-")));
        assertTrue(first.proposalIds().stream().anyMatch(id -> id.startsWith("accept-")));
        assertTrue(first.holdoutIds().size() > 0);
        assertTrue(first.proposalIds().size() > 0);
        assertTrue(first.describe().contains("seed=" + CorpusPartition.DEFAULT_SEED));
    }

    @Test
    void holdoutIsDoubleBlindEnforced() {
        CorpusPartition partition = CorpusPartition.build(corpus(), 0.30, CorpusPartition.DEFAULT_SEED);
        String holdoutCase = partition.holdoutIds().iterator().next();
        String proposalCase = partition.proposalIds().iterator().next();

        assertThrows(IllegalStateException.class,
                () -> partition.enforceBlind(holdoutCase, "proposer-input"));
        assertDoesNotThrow(() -> partition.enforceBlind(proposalCase, "proposer-input"));
    }

    @Test
    void smokeTierNeverContainsHoldout() {
        CorpusPartition partition = CorpusPartition.build(corpus(), 0.30, CorpusPartition.DEFAULT_SEED);
        List<String> smoke = partition.smokeIds(10, corpus());

        assertEquals(10, smoke.size());
        for (String id : smoke) {
            assertFalse(partition.isHoldout(id), id + " leaked into the smoke tier");
        }
        // deterministic
        assertEquals(smoke, partition.smokeIds(10, corpus()));
    }

    @Test
    void partitionFileRoundTrips() throws Exception {
        CorpusPartition partition = CorpusPartition.build(corpus(), 0.30, "seed-x");
        partition.save(temp.resolve("partition.json"));

        CorpusPartition reloaded = CorpusPartition.load(temp.resolve("partition.json"));
        assertEquals(partition.holdoutIds(), reloaded.holdoutIds());
        assertEquals(partition.proposalIds(), reloaded.proposalIds());

        // same seed rebuilds the identical split — the pinned file and a rebuild agree
        CorpusPartition rebuilt = CorpusPartition.build(corpus(), 0.30, "seed-x");
        assertEquals(rebuilt.holdoutIds(), reloaded.holdoutIds());
    }

    @Test
    void proposalAndHoldoutSidesPartitionTheCorpusExactly() {
        CorpusPartition partition = CorpusPartition.build(corpus(), 0.30, CorpusPartition.DEFAULT_SEED);
        List<GoldenCase> all = corpus();

        List<GoldenCase> proposal = partition.proposalCases(all);
        List<GoldenCase> holdout = partition.holdoutCases(all);
        assertEquals(all.size(), proposal.size() + holdout.size());
        proposal.forEach(c -> assertFalse(partition.isHoldout(c.id())));
        holdout.forEach(c -> assertTrue(partition.isHoldout(c.id())));
    }

    @Test
    void bucketIsAStableHashFunction() {
        double a = CorpusPartition.bucket("s", "accept", "accept-01");
        double b = CorpusPartition.bucket("s", "accept", "accept-01");
        double c = CorpusPartition.bucket("s2", "accept", "accept-01");
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertTrue(a >= 0.0 && a < 1.0);
    }
}
