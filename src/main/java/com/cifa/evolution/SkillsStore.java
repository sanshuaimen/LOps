package com.cifa.evolution;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The behavior-asset store: one {@code skills.json} document holding every
 * skill ever proposed, in any {@link SkillStatus} — including ARCHIVED
 * tombstones. Same posture as {@code FailureMemoryStore}: a local JSON file,
 * explicit caller-driven writes, corruption degrades to empty rather than
 * blocking the agent.
 *
 * <p>Governance guarantees baked into the write paths (each pinned by an
 * invariant test):</p>
 * <ul>
 *   <li><b>No mutation without an audit line</b> — every transition writes to
 *       the {@link SkillAuditLog} in the same call.</li>
 *   <li><b>State machine is enforced</b> — illegal transitions are refused,
 *       ARCHIVED is terminal (tombstones are never resurrected in place).</li>
 *   <li><b>Routing promotions need a named approver</b> — ROUTING skills
 *       change the deterministic control plane, so promote() refuses without
 *       {@code approver}; the CLI passes EVOLUTION_APPROVAL.</li>
 *   <li><b>Conflicts quarantine, never overwrite</b> — append() runs the
 *       {@link ConflictDetector} and stores contradicting candidates as
 *       CONFLICTED; the active rule keeps governing.</li>
 *   <li><b>Gate scores are recorded before promotion</b> — recordValidation()
 *       stamps wins/losses + dataset version onto the rule, so every ACTIVE
 *       skill can show what it was measured on.</li>
 * </ul>
 */
public final class SkillsStore {

    private final Path file;
    private final SkillAuditLog audit;
    private final List<SkillRule> skills;

    public SkillsStore(Path file, SkillAuditLog audit) {
        this.file = file;
        this.audit = audit;
        this.skills = load(file);
    }

    private static List<SkillRule> load(Path file) {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            JSONObject json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            JSONArray array = json.optJSONArray("skills");
            List<SkillRule> loaded = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    loaded.add(SkillRule.fromJson(array.getJSONObject(i)));
                }
            }
            return loaded;
        } catch (Exception e) {
            // A corrupted skills file degrades to empty and is rewritten on the
            // next write — a broken store must never take the agent down.
            return new ArrayList<>();
        }
    }

    public List<SkillRule> all() {
        return List.copyOf(skills);
    }

    public List<SkillRule> active() {
        return skills.stream().filter(SkillRule::isActive).toList();
    }

    public int size() {
        return skills.size();
    }

    public Optional<SkillRule> byId(String id) {
        return skills.stream().filter(skill -> skill.id().equals(id)).findFirst();
    }

    /**
     * Stores a proposed skill: NEW advice lands as CANDIDATE, contradictions
     * land as CONFLICTED (quarantined, the active rule stays in charge),
     * duplicates are refused outright (the negative path already exists).
     */
    public SkillRule append(SkillRule candidate, String actor, String basedOn) throws IOException {
        if (byId(candidate.id()).isPresent()) {
            throw new IllegalArgumentException("skill id already exists: " + candidate.id()
                    + " — identical advice deduplicates; check the existing rule or archive history");
        }
        ConflictDetector.Verdict verdict = ConflictDetector.classify(candidate, skills);
        SkillRule stored = switch (verdict.classification()) {
            case NEW -> candidate.withStatus(SkillStatus.CANDIDATE);
            case CONFLICT -> candidate.withStatus(SkillStatus.CONFLICTED).withRejection(verdict.reason());
            case DUPLICATE -> throw new IllegalArgumentException("rejected as duplicate: " + verdict.reason());
        };
        skills.add(stored);
        write();
        audit.append(actor, SkillAuditLog.PROPOSE, stored.id(), null, stored.status().name(),
                verdict.reason(), basedOn);
        if (stored.status() == SkillStatus.CONFLICTED) {
            audit.append(actor, SkillAuditLog.CONFLICT, stored.id(), SkillStatus.CANDIDATE.name(),
                    SkillStatus.CONFLICTED.name(), verdict.reason(), basedOn);
        }
        return stored;
    }

    /** Records paired-gate results on a candidate BEFORE the promote/reject
     * decision, so the stored rule always carries its own evidence. */
    public SkillRule recordValidation(String id, String datasetVersion, int wins, int losses,
                                      String actor, String basedOn) throws IOException {
        SkillRule rule = require(id);
        SkillRule updated = rule.withValidation(datasetVersion, wins, losses);
        replace(updated);
        audit.append(actor, SkillAuditLog.GATE, id, rule.status().name(), rule.status().name(),
                "paired gate: " + wins + " wins / " + losses + " losses on dataset " + datasetVersion, basedOn);
        return updated;
    }

    /** CANDIDATE/CONFLICTED → ACTIVE. ROUTING kind requires a named approver —
     * the plan's high-risk human gate; PROMOTION without one is refused. */
    public SkillRule promote(String id, String approver, String reason, String basedOn) throws IOException {
        SkillRule rule = require(id);
        if (rule.kind() == SkillKind.ROUTING && (approver == null || approver.isBlank())) {
            throw new IllegalArgumentException("promoting ROUTING skill '" + id
                    + "' requires a named approver (EVOLUTION_APPROVAL) — routing changes the deterministic control plane");
        }
        transition(rule, SkillStatus.ACTIVE);
        SkillRule promoted = rule.withStatus(SkillStatus.ACTIVE).promotedBy(
                approver == null ? "" : approver);
        replace(promoted);
        audit.append(approver == null || approver.isBlank() ? "system" : approver,
                SkillAuditLog.PROMOTE, id, rule.status().name(), SkillStatus.ACTIVE.name(),
                reason, basedOn);
        return promoted;
    }

    /** CANDIDATE/CONFLICTED → ARCHIVED with the reason kept on the tombstone;
     * the gate's negative verdict is preserved as first-class knowledge. */
    public SkillRule reject(String id, String reason, String actor, String basedOn) throws IOException {
        SkillRule rule = require(id);
        transition(rule, SkillStatus.ARCHIVED);
        SkillRule archived = rule.withStatus(SkillStatus.ARCHIVED).withRejection(reason);
        replace(archived);
        audit.append(actor, SkillAuditLog.REJECT, id, rule.status().name(), SkillStatus.ARCHIVED.name(),
                reason, basedOn);
        return archived;
    }

    /** ACTIVE → SUPERSEDED with the lineage link to the successor. */
    public SkillRule supersede(String id, String successorId, String actor, String reason,
                               String basedOn) throws IOException {
        SkillRule rule = require(id);
        transition(rule, SkillStatus.SUPERSEDED);
        SkillRule superseded = rule.supersededBy(successorId);
        replace(superseded);
        audit.append(actor, SkillAuditLog.SUPERSEDE, id, SkillStatus.ACTIVE.name(),
                SkillStatus.SUPERSEDED.name(), reason + " (successor: " + successorId + ")", basedOn);
        return superseded;
    }

    /** ACTIVE → STALE (decay's dormancy: kept, tombstone intact, no longer
     * injected by the router). */
    public SkillRule markStale(String id, String actor, String reason, String basedOn) throws IOException {
        SkillRule rule = require(id);
        transition(rule, SkillStatus.STALE);
        SkillRule stale = rule.withStatus(SkillStatus.STALE);
        replace(stale);
        audit.append(actor, SkillAuditLog.STALE, id, SkillStatus.ACTIVE.name(), SkillStatus.STALE.name(),
                reason, basedOn);
        return stale;
    }

    /** STALE → ACTIVE (explicit revival after evidence the skill is used again). */
    public SkillRule reactivate(String id, String actor, String reason, String basedOn) throws IOException {
        SkillRule rule = require(id);
        transition(rule, SkillStatus.ACTIVE);
        SkillRule active = rule.withStatus(SkillStatus.ACTIVE);
        replace(active);
        audit.append(actor, SkillAuditLog.REACTIVATE, id, SkillStatus.STALE.name(), SkillStatus.ACTIVE.name(),
                reason, basedOn);
        return active;
    }

    /** Terminal tombstone for any non-CANDIDATE status; the entry stays in the
     * file forever — deletion is not a store operation. */
    public SkillRule archive(String id, String actor, String reason, String basedOn) throws IOException {
        SkillRule rule = require(id);
        transition(rule, SkillStatus.ARCHIVED);
        SkillRule archived = rule.withStatus(SkillStatus.ARCHIVED);
        replace(archived);
        audit.append(actor, SkillAuditLog.ARCHIVE, id, rule.status().name(), SkillStatus.ARCHIVED.name(),
                reason, basedOn);
        return archived;
    }

    private void transition(SkillRule rule, SkillStatus to) {
        if (!SkillStatus.canTransition(rule.status(), to)) {
            throw new IllegalStateException("illegal skill transition " + rule.id() + ": "
                    + rule.status() + " → " + to + " (see SkillStatus for the legal edges)");
        }
    }

    private SkillRule require(String id) {
        return byId(id).orElseThrow(() -> new IllegalArgumentException("no skill with id: " + id));
    }

    private void replace(SkillRule updated) throws IOException {
        for (int i = 0; i < skills.size(); i++) {
            if (skills.get(i).id().equals(updated.id())) {
                skills.set(i, updated);
                write();
                return;
            }
        }
        throw new IllegalArgumentException("no skill with id: " + updated.id());
    }

    /** Exact bytes on disk — the snapshot/rollback contract hashes THIS. */
    public byte[] bytes() throws IOException {
        return serialize().toString(2).getBytes(StandardCharsets.UTF_8) ;
    }

    private void write() throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, serialize().toString(2), StandardCharsets.UTF_8);
    }

    private JSONObject serialize() {
        JSONObject json = new JSONObject();
        json.put("version", 1);
        JSONArray array = new JSONArray();
        skills.forEach(skill -> array.put(skill.toJson()));
        json.put("skills", array);
        return json;
    }
}
