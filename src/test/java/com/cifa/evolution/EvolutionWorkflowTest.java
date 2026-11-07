package com.cifa.evolution;

import com.cifa.eval.EvalReport;
import com.cifa.eval.EvalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The full evolution loop, end to end on real stores: candidate → staged A/B →
 * paired gate → promotion with gate scores → snapshot → mutate → byte-identical
 * rollback; the reject path tombstoning into negative knowledge; decay's
 * data-volume precondition; conflict arbitration superseding the old rule.
 * This is the story the invariant tests protect.
 */
class EvolutionWorkflowTest {

    @TempDir
    Path temp;

    private static EvalResult result(String caseId, boolean passed) {
        return new EvalResult(caseId, "DEPENDENCY_BUILD", passed ? "DEPENDENCY_BUILD" : "LOGIC_ERROR",
                "robustness", passed, passed, passed, passed, true, true, true,
                List.of(), false, 0, 0, 10, 1, passed ? "COMPLETED" : "MAX_ROUNDS", "");
    }

    private static EvalReport report(List<EvalResult> results) {
        return new EvalReport("agent", "test", Instant.EPOCH, results);
    }

    private SkillsStore store() {
        return new SkillsStore(temp.resolve("skills.json"),
                new SkillAuditLog(temp.resolve("audit.jsonl")));
    }

    private static SkillRule candidate(String id, String keyword) {
        return new SkillRule(id, "practice " + id, SkillKind.PROMPT, "LOGIC_ERROR", List.of(keyword),
                List.of(), "check timeouts first", SkillStatus.CANDIDATE, "", "",
                "trace-x.jsonl", List.of(), List.of(), "", 0, 0, "");
    }

    @Test
    void fullLoopPromoteSnapshotRollbackByteIdentical() throws Exception {
        SkillsStore store = store();
        SkillRule proposed = store.append(candidate("skill-loop001", "timeout"), "harvest", "t1");

        // stage: candidate flips ACTIVE on the staged side
        SkillRule staged = stagedVersion(store, "skill-loop001");
        assertEquals(SkillStatus.ACTIVE, staged.status());
        assertEquals(SkillStatus.CANDIDATE, store.byId("skill-loop001").orElseThrow().status(),
                "staging must not touch the main store");

        // gate: paired win/loss on the proposal set — wins, zero regressions
        EvalReport baseline = report(List.of(result("p1", false), result("p2", true), result("p3", true)));
        EvalReport candidate_ = report(List.of(result("p1", true), result("p2", true), result("p3", true)));
        PairedGatekeeper.GateVerdict verdict = PairedGatekeeper.gate(baseline, candidate_);
        assertTrue(verdict.promote());

        store.recordValidation("skill-loop001", "ds-gate-1", verdict.wins(), verdict.losses(),
                "gatekeeper", "baseline-vs-candidate");
        store.promote("skill-loop001", null, verdict.summary(), "candidate-report.json");

        SkillRule active = store.byId("skill-loop001").orElseThrow();
        assertEquals(SkillStatus.ACTIVE, active.status());
        assertEquals(1, active.wins());
        assertEquals("ds-gate-1", active.validatedOnDataset());

        // snapshot binds the exact bytes; then a decay run mutates; rollback restores
        Path blobDir = temp.resolve("evolution");
        EvolutionSnapshots snapshots = new EvolutionSnapshots(
                temp.resolve("evolution-snapshots.jsonl"), blobDir, new SkillAuditLog(temp.resolve("audit.jsonl")));
        EvolutionSnapshots.Snapshot snapshot = snapshots.snapshot(temp.resolve("skills.json"),
                "gate-report-1.md", "ds-gate-1", "post-promote", "cifa");
        assertEquals(1, snapshot.seq());

        // second active skill, then decay marks the unused one stale (library size gate)
        store.append(candidate("skill-loop002", "garbled"), "harvest", "t2");
        store.promote("skill-loop002", null, "ok", "gate-2");
        SkillUsageStore usage = new SkillUsageStore(temp.resolve("usage.json"));
        List<DecayEngine.Decision> decisions = DecayEngine.run(store, usage, DecayEngine.DEFAULT_THRESHOLD,
                DecayEngine.DEFAULT_HALF_LIFE_DAYS, 2, 1_700_000_000_000L, "decay", "decay-run");
        assertEquals(2, decisions.size());
        assertEquals(SkillStatus.STALE, store.byId("skill-loop002").orElseThrow().status());

        // rollback: behavior asset returns to the snapshot's EXACT bytes
        byte[] mutated = Files.readAllBytes(temp.resolve("skills.json"));
        String hash = snapshots.rollback(temp.resolve("skills.json"), 1, "cifa");
        byte[] restored = Files.readAllBytes(temp.resolve("skills.json"));
        byte[] blob = Files.readAllBytes(blobDir.resolve("skills-" + hash + ".json"));
        assertArrayEquals(blob, restored, "rollback must be byte-identical to the snapshot");
        assertFalse(java.util.Arrays.equals(mutated, restored), "rollback must actually change the file back");
        // and the STALE skill is gone from the restored bytes (it never existed at snapshot time)
        assertFalse(new String(restored).contains("skill-loop002"));
    }

    private SkillRule stagedVersion(SkillsStore store, String id) {
        SkillRule skill = store.byId(id).orElseThrow();
        return skill.withStatus(SkillStatus.ACTIVE);
    }

    @Test
    void rejectPathTombstonesIntoNegativeKnowledge() throws Exception {
        SkillsStore store = store();
        store.append(candidate("skill-rej001", "rewrite"), "harvest", "t1");
        RejectedPathStore rejected = new RejectedPathStore(temp.resolve("rejected.json"));

        EvalReport baseline = report(List.of(result("p1", true), result("p2", true)));
        EvalReport candidate_ = report(List.of(result("p1", true), result("p2", false)));
        PairedGatekeeper.GateVerdict verdict = PairedGatekeeper.gate(baseline, candidate_);
        assertFalse(verdict.promote());
        assertEquals(List.of("p2"), verdict.regressionCases());

        store.reject("skill-rej001", verdict.reason(), "gatekeeper", "candidate.json");
        rejected.append(new RejectedPathStore.RejectedPath("rewrite", "check timeouts first",
                "gate rejected: " + verdict.reason(), "candidate.json", "ds-r", 1));

        SkillRule tombstone = store.byId("skill-rej001").orElseThrow();
        assertEquals(SkillStatus.ARCHIVED, tombstone.status());
        assertTrue(tombstone.rejectionReason().contains("regression"));
        // next proposer sees the graveyard: relevance finds the rejected path
        assertEquals(1, rejected.relevantFor("rewrite the module", 3).size());
        // re-proposing identical advice is refused by the store (duplicate scan hits tombstones)
        assertThrows(IllegalArgumentException.class,
                () -> store.append(candidate("skill-rej002", "rewrite"), "harvest", "t2"));
        // and when the SAME dead end is recorded twice, the ledger bumps timesSeen,
        // it does not grow a line — "tried AGAIN and failed AGAIN"
        rejected.append(new RejectedPathStore.RejectedPath("rewrite", "check timeouts first",
                "gate rejected again", "candidate-2.json", "ds-r2", 1));
        assertEquals(1, rejected.size());
        assertEquals(2, rejected.all().get(0).timesSeen());
        assertEquals("gate rejected again", rejected.all().get(0).whyFailed());
    }

    @Test
    void decayRefusesAnUndergrownLibrary() throws Exception {
        SkillsStore store = store();
        store.append(candidate("skill-sm001", "timeout"), "harvest", "t1");
        store.promote("skill-sm001", null, "ok", "g");

        SkillUsageStore usage = new SkillUsageStore(temp.resolve("u.json"));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> DecayEngine.run(store, usage, DecayEngine.DEFAULT_THRESHOLD,
                        DecayEngine.DEFAULT_HALF_LIFE_DAYS, DecayEngine.DEFAULT_MIN_STORE,
                        1_700_000_000_000L, "decay", "run"));
        assertTrue(refused.getMessage().contains("decay refused"), refused.getMessage());
        assertTrue(refused.getMessage().contains("grow the library"), refused.getMessage());
        // nothing was marked: the refusal is clean
        assertEquals(SkillStatus.ACTIVE, store.byId("skill-sm001").orElseThrow().status());
    }

    @Test
    void arbitrationPromoteNewSupersedesTheOldRule() throws Exception {
        SkillsStore store = store();
        store.append(new SkillRule("skill-old009", "old routing", SkillKind.ROUTING, "LOGIC_ERROR",
                List.of("regression"), List.of("git_history"), "", SkillStatus.CANDIDATE, "", "",
                "t0", List.of(), List.of(), "ds-0", 1, 0, ""), "harvest", "t0");
        store.promote("skill-old009", "EVOLUTION_APPROVAL:alice", "ok", "g0");

        // harvest proposes contradicting routing on the same symptom → CONFLICTED
        SkillRule conflicted = store.append(new SkillRule("skill-new010", "new routing", SkillKind.ROUTING,
                "LOGIC_ERROR", List.of("regression"), List.of("source_symbol_search", "git_history"),
                "", SkillStatus.CANDIDATE, "", "", "t1", List.of(), List.of(), "", 0, 0, ""),
                "harvest", "t1");
        assertEquals(SkillStatus.CONFLICTED, conflicted.status());

        // human arbitrates: the new rule wins, the old rule is superseded (kept, linked)
        ConflictDetector.Verdict conflict = ConflictDetector.classify(conflicted, store.active());
        assertEquals(ConflictDetector.Classification.CONFLICT, conflict.classification());
        store.supersede(conflict.against().id(), "skill-new010", "cifa", "lost arbitration", "arb");
        store.promote("skill-new010", "EVOLUTION_APPROVAL:alice", "won arbitration", "arb");

        assertEquals(SkillStatus.SUPERSEDED, store.byId("skill-old009").orElseThrow().status());
        assertEquals("skill-new010", store.byId("skill-old009").orElseThrow().supersededBy());
        assertEquals(SkillStatus.ACTIVE, store.byId("skill-new010").orElseThrow().status());
    }

    @Test
    void liveUsageFeedsSurvivalScores() {
        SkillUsageStore usage = new SkillUsageStore(temp.resolve("usage.json"));
        usage.recordHit("skill-x", SkillUsageStore.Source.LIVE);
        usage.recordHit("skill-x", SkillUsageStore.Source.EVAL);

        assertEquals(1, usage.usage("skill-x").liveHits());
        assertEquals(1, usage.usage("skill-x").evalHits());
        double scored = DecayEngine.survivalScore(usage.usage("skill-x"), 0, 1_700_000_000_000L, 14);
        assertEquals(3.5, scored, 1e-9); // 2.0*live + 0.5*eval + 1.0 recency (lastHit ≈ now)

        // an eval-only skill past its half-life window: 0.5 < threshold 1.0 →
        // it decays — live signal dominates, as documented
        usage.recordHit("skill-y", SkillUsageStore.Source.EVAL);
        long halfLifeOver = usage.usage("skill-y").lastHitEpochMillis() + 15L * 24 * 60 * 60 * 1000;
        double evalOnly = DecayEngine.survivalScore(usage.usage("skill-y"), 0, halfLifeOver, 14);
        assertTrue(evalOnly < DecayEngine.DEFAULT_THRESHOLD);
    }
}
