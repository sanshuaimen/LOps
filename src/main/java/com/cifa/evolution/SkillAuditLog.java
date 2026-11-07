package com.cifa.evolution;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only audit trail for every skill mutation (the plan's minimal-but-
 * complete meta-governance): who, when, based on what evidence, moved which
 * rule from which status to which. One JSONL line per event; lines are never
 * rewritten or truncated — the log is the "who touched the behavior" half of
 * the answer to "why does the agent behave this way".
 *
 * <p>Every mutation path in {@link SkillsStore} writes here in the same
 * call; there is no store mutation without an audit line, and the invariant
 * test pins that.</p>
 */
public final class SkillAuditLog {

    /** One audit record. {@code basedOn} carries the evidence handle (trace
     * file / case ids / gate report name) that justified the change. */
    public record AuditEntry(long seq, Instant timestamp, String actor, String action,
                             String skillId, String fromStatus, String toStatus,
                             String reason, String basedOn) {
    }

    // Action vocabulary — a closed set so log consumers can rely on it.
    public static final String PROPOSE = "propose";
    public static final String GATE = "gate";
    public static final String PROMOTE = "promote";
    public static final String REJECT = "reject";
    public static final String CONFLICT = "conflict";
    public static final String ARBITRATE = "arbitrate";
    public static final String STALE = "stale";
    public static final String SUPERSEDE = "supersede";
    public static final String REACTIVATE = "reactivate";
    public static final String ARCHIVE = "archive";
    public static final String SNAPSHOT = "snapshot";
    public static final String ROLLBACK = "rollback";

    private final Path file;

    public SkillAuditLog(Path file) {
        this.file = file;
    }

    public synchronized void append(String actor, String action, String skillId,
                                    String fromStatus, String toStatus,
                                    String reason, String basedOn) throws IOException {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("audit actor is required — every mutation names its human or system");
        }
        long seq = countLines() + 1;
        JSONObject json = new JSONObject();
        json.put("seq", seq);
        json.put("ts", Instant.now().toString());
        json.put("actor", actor);
        json.put("action", action);
        json.put("skillId", skillId == null ? "" : skillId);
        json.put("from", fromStatus == null ? "" : fromStatus);
        json.put("to", toStatus == null ? "" : toStatus);
        json.put("reason", reason == null ? "" : reason);
        json.put("basedOn", basedOn == null ? "" : basedOn);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, json.toString() + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    /** Reads the full trail; a missing file is an empty trail, a torn last line
     * degrades to the lines that parse (append-only writes keep torn tails rare
     * and the prefix is still trustworthy). */
    public synchronized List<AuditEntry> entries() throws IOException {
        List<AuditEntry> entries = new ArrayList<>();
        if (!Files.exists(file)) {
            return entries;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JSONObject json = new JSONObject(line);
                entries.add(new AuditEntry(
                        json.optLong("seq", entries.size() + 1),
                        Instant.parse(json.optString("ts", Instant.EPOCH.toString())),
                        json.optString("actor", ""),
                        json.optString("action", ""),
                        json.optString("skillId", ""),
                        json.optString("from", ""),
                        json.optString("to", ""),
                        json.optString("reason", ""),
                        json.optString("basedOn", "")));
            } catch (Exception e) {
                // torn tail: keep the parseable prefix
                break;
            }
        }
        return entries;
    }

    private synchronized long countLines() throws IOException {
        if (!Files.exists(file)) {
            return 0;
        }
        long count = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                count++;
            }
        }
        return count;
    }
}
