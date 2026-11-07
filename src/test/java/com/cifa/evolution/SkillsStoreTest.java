package com.cifa.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Evolution plan invariants, mechanically pinned at the asset layer: state
 * machine edges, tombstones that are never resurrected or deleted, the
 * routing-promotion approval gate, conflict quarantine (the active rule keeps
 * governing), gate-score provenance, and "no mutation without an audit line".
 */
class SkillsStoreTest {

    @TempDir
    Path temp;

    private SkillsStore store(String name) {
        Path file = temp.resolve(name);
        return new SkillsStore(file, new SkillAuditLog(temp.resolve(name + "-audit.jsonl")));
    }

    private static SkillRule promptSkill(String id, String name, String category, String keyword,
                                         String instruction) {
        return new SkillRule(id, name, SkillKind.PROMPT, category, List.of(keyword), List.of(),
                instruction, SkillStatus.CANDIDATE, "", "", "trace-t1", List.of("case-1"),
                List.of("SRC-1"), "", 0, 0, "");
    }

    private static SkillRule routingSkill(String id, String name, String category, String keyword,
                                          List<String> tools) {
        return new SkillRule(id, name, SkillKind.ROUTING, category, List.of(keyword), tools,
                "", SkillStatus.CANDIDATE, "", "", "trace-t1", List.of("case-1"),
                List.of("SRC-1"), "", 0, 0, "");
    }

    @Test
    void ruleShapeIsEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new SkillRule("s-1", "", SkillKind.PROMPT,
                "LOGIC_ERROR", List.of("x"), List.of(), "text", SkillStatus.CANDIDATE,
                "", "", "", List.of(), List.of(), "", 0, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new SkillRule("s-1", "n", SkillKind.PROMPT,
                "LOGIC_ERROR", List.of(), List.of(), "text", SkillStatus.CANDIDATE,
                "", "", "", List.of(), List.of(), "", 0, 0, ""));
        // ROUTING with prompt text is a category error: routing compiles into the
        // deterministic layer, it must never smuggle free prompt through
        assertThrows(IllegalArgumentException.class, () -> new SkillRule("s-1", "n", SkillKind.ROUTING,
                "LOGIC_ERROR", List.of("x"), List.of("git_history"), "sneaky prompt",
                SkillStatus.CANDIDATE, "", "", "", List.of(), List.of(), "", 0, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new SkillRule("s-1", "n", SkillKind.ROUTING,
                "LOGIC_ERROR", List.of("x"), List.of(), "", SkillStatus.CANDIDATE,
                "", "", "", List.of(), List.of(), "", 0, 0, ""));
    }

    @Test
    void newAdviceLandsAsCandidateAndAudits() throws IOException {
        SkillsStore store = store("skills.json");
        SkillRule stored = store.append(
                promptSkill("skill-aaa111", "check surefire first", "LOGIC_ERROR", "surefire",
                        "Before citing INFRA_FLAKY, open the surefire report."),
                "harvest", "trace-agent-1.jsonl");

        assertEquals(SkillStatus.CANDIDATE, stored.status());
        assertEquals(1, store.size());
        SkillAuditLog.AuditEntry line = auditLines(name(store)).get(0);
        assertEquals(SkillAuditLog.PROPOSE, line.action());
        assertEquals("harvest", line.actor());
        assertEquals("trace-agent-1.jsonl", line.basedOn());
    }

    @Test
    void conflictQuarantinesCandidateAndActiveKeepsGoverning() throws IOException {
        SkillsStore store = store("skills.json");
        store.append(routingSkill("skill-old001", "regression→git", "LOGIC_ERROR", "regression",
                List.of("git_history", "source_location")), "harvest", "t1");
        store.promote("skill-old001", "alice", "validated", "gate-1");

        SkillRule conflicted = store.append(
                routingSkill("skill-new002", "regression→symbol first", "LOGIC_ERROR", "regression",
                        List.of("source_symbol_search", "git_history")), "harvest", "t2");

        assertEquals(SkillStatus.CONFLICTED, conflicted.status());
        // the ACTIVE rule keeps governing — quarantine never overwrites
        assertEquals(SkillStatus.ACTIVE, store.byId("skill-old001").orElseThrow().status());
        // audit trail: propose-old, promote-old, propose-new, conflict-new
        List<SkillAuditLog.AuditEntry> lines = auditLines(name(store));
        assertEquals(4, lines.size());
        assertEquals(SkillAuditLog.CONFLICT, lines.get(3).action());
    }

    @Test
    void duplicateAdviceIsRefusedNotStored() throws IOException {
        SkillsStore store = store("skills.json");
        store.append(routingSkill("skill-dup001", "regression→git", "LOGIC_ERROR", "regression",
                List.of("git_history", "source_location")), "harvest", "t1");

        IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
                () -> store.append(routingSkill("skill-dup002", "regression→git again",
                        "LOGIC_ERROR", "regression", List.of("git_history", "source_location")),
                        "harvest", "t2"));
        assertTrue(blocked.getMessage().contains("duplicate"), blocked.getMessage());
        assertEquals(1, store.size());
    }

    @Test
    void routingPromotionRequiresNamedApprover() throws IOException {
        SkillsStore store = store("skills.json");
        store.append(routingSkill("skill-rt0001", "regression→git", "LOGIC_ERROR", "regression",
                List.of("git_history")), "harvest", "t1");

        // PROMPT skills promote without an approver; ROUTING skills do not
        IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
                () -> store.promote("skill-rt0001", "", "looks good", "gate-1"));
        assertTrue(blocked.getMessage().contains("approver"), blocked.getMessage());

        SkillRule promoted = store.promote("skill-rt0001", "EVOLUTION_APPROVAL:alice", "validated", "gate-1");
        assertEquals(SkillStatus.ACTIVE, promoted.status());
        assertEquals("EVOLUTION_APPROVAL:alice", promoted.promotedBy());
    }

    @Test
    void archivedSkillsAreEternalTombstones() throws IOException {
        SkillsStore store = store("skills.json");
        store.append(promptSkill("skill-tmb001", "old practice", "LOGIC_ERROR", "timeout",
                "Check timeouts first."), "harvest", "t1");
        store.reject("skill-tmb001", "gate: 0 wins / 2 losses", "gatekeeper", "gate-1");

        SkillsStore reloaded = store("skills.json");
        assertEquals(1, reloaded.size()); // tombstone kept, not physically deleted
        assertEquals(SkillStatus.ARCHIVED, reloaded.byId("skill-tmb001").orElseThrow().status());
        assertEquals("gate: 0 wins / 2 losses",
                reloaded.byId("skill-tmb001").orElseThrow().rejectionReason());

        // ARCHIVED is terminal: no resurrection in place
        assertThrows(IllegalStateException.class, () -> reloaded.promote("skill-tmb001", "bob", "no", "x"));
        assertThrows(IllegalStateException.class, () -> reloaded.archive("skill-tmb001", "bob", "no", "x"));
    }

    @Test
    void illegalTransitionsAreRefused() throws IOException {
        SkillsStore store = store("skills.json");
        store.append(promptSkill("skill-ill001", "practice", "LOGIC_ERROR", "timeout", "Check timeouts."),
                "harvest", "t1");
        // CANDIDATE → STALE skips the validation gate entirely: refused
        assertThrows(IllegalStateException.class,
                () -> store.markStale("skill-ill001", "decay", "unused", "decay-run"));
        // CANDIDATE → SUPERSEDED: refused
        assertThrows(IllegalStateException.class,
                () -> store.supersede("skill-ill001", "skill-other", "arb", "no", "x"));
    }

    @Test
    void gateScoresStampProvenanceBeforeDecision() throws IOException {
        SkillsStore store = store("skills.json");
        store.append(promptSkill("skill-val001", "practice", "LOGIC_ERROR", "timeout", "Check timeouts."),
                "harvest", "t1");
        SkillRule validated = store.recordValidation("skill-val001", "ds-abc123", 4, 0,
                "gatekeeper", "gate-report-1.md");

        assertEquals(4, validated.wins());
        assertEquals(0, validated.losses());
        assertEquals("ds-abc123", validated.validatedOnDataset());
        assertEquals("ds-abc123", store.byId("skill-val001").orElseThrow().validatedOnDataset());
    }

    @Test
    void supersedeKeepsTheLineageLink() throws IOException {
        SkillsStore store = store("skills.json");
        store.append(promptSkill("skill-old003", "old", "LOGIC_ERROR", "timeout", "old advice."),
                "harvest", "t1");
        store.promote("skill-old003", "alice", "ok", "gate-1");
        store.append(promptSkill("skill-new004", "new", "LOGIC_ERROR", "timeout", "new advice."),
                "harvest", "t2");
        store.promote("skill-new004", "alice", "ok", "gate-2");

        SkillRule superseded = store.supersede("skill-old003", "skill-new004", "arb",
                "new gate beat old", "gate-2");
        assertEquals(SkillStatus.SUPERSEDED, superseded.status());
        assertEquals("skill-new004", store.byId("skill-old003").orElseThrow().supersededBy());
    }

    @Test
    void staleRoundTripsAndReactivates() throws IOException {
        SkillsStore store = store("skills.json");
        store.append(promptSkill("skill-stl001", "practice", "LOGIC_ERROR", "timeout", "Check timeouts."),
                "harvest", "t1");
        store.promote("skill-stl001", "alice", "ok", "gate-1");
        store.markStale("skill-stl001", "decay", "survival below threshold", "decay-run-1");
        assertEquals(SkillStatus.STALE, store.byId("skill-stl001").orElseThrow().status());

        store.reactivate("skill-stl001", "alice", "used again", "live-hit");
        assertEquals(SkillStatus.ACTIVE, store.byId("skill-stl001").orElseThrow().status());
    }

    @Test
    void everyMutationLeavesAnAuditLineAndTheLogIsAppendOnly() throws IOException {
        Path skillsFile = temp.resolve("skills-audit.json");
        Path auditFile = temp.resolve("skills-audit.jsonl");
        SkillsStore store = new SkillsStore(skillsFile, new SkillAuditLog(auditFile));

        store.append(promptSkill("skill-aud001", "p", "LOGIC_ERROR", "timeout", "adv"),
                "harvest", "t1");                     // 1 propose
        store.recordValidation("skill-aud001", "ds-1", 2, 0, "gatekeeper", "r1"); // 2 gate
        store.promote("skill-aud001", "alice", "ok", "r1");       // 3 promote
        store.markStale("skill-aud001", "decay", "unused", "d1"); // 4 stale
        store.reactivate("skill-aud001", "alice", "again", "h1"); // 5 reactivate
        store.supersede("skill-aud001", "skill-aud002", "arb", "newer", "r2"); // 6 supersede
        store.archive("skill-aud001", "bob", "cleanup", "r3");    // 7 archive

        SkillAuditLog log = new SkillAuditLog(auditFile);
        List<SkillAuditLog.AuditEntry> entries = log.entries();
        assertEquals(7, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(i + 1, entries.get(i).seq());
            assertTrue(!entries.get(i).actor().isBlank());
        }
        assertEquals(SkillAuditLog.ARCHIVE, entries.get(6).action());
        // append-only: no rewrite path exists — appending keeps the prefix intact
        int before = log.entries().size();
        log.append("bob", SkillAuditLog.SNAPSHOT, "", "", "", "snap", "r4");
        assertEquals(before + 1, log.entries().size());
        assertEquals(SkillAuditLog.ARCHIVE, log.entries().get(before - 1).action());
    }

    @Test
    void corruptedSkillsFileDegradesToEmptyNotCrash() throws IOException {
        Path broken = temp.resolve("broken-skills.json");
        Files.writeString(broken, "{not json at all");
        assertEquals(0, new SkillsStore(broken, new SkillAuditLog(temp.resolve("a.jsonl"))).size());
    }

    @Test
    void contentAddressedIdsAreStable() {
        String a = SkillRule.newId(SkillKind.ROUTING, "LOGIC_ERROR", List.of("regression"), "git_history");
        String b = SkillRule.newId(SkillKind.ROUTING, "LOGIC_ERROR", List.of("regression"), "git_history");
        String c = SkillRule.newId(SkillKind.ROUTING, "LOGIC_ERROR", List.of("regression"), "flaky_history");
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertTrue(a.startsWith("skill-"));
    }

    private static String name(SkillsStore store) {
        return "skills.json";
    }

    private List<SkillAuditLog.AuditEntry> auditLines(String skillsName) throws IOException {
        return new SkillAuditLog(temp.resolve(skillsName + "-audit.jsonl")).entries();
    }
}
