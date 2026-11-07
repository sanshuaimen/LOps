package com.cifa.evolution;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Per-skill usage telemetry feeding the decay mechanism's survival score:
 * retrieval hits (live diagnosis vs eval runs, counted separately — a skill
 * only exercised by the exam is not the same signal as one exercised by real
 * investigations), plus the last-hit timestamp for the recency term.
 *
 * <p>Deliberately a SEPARATE file from {@code skills.json}: the curated
 * behavior asset keeps its git history clean, while this file churns on every
 * run. Writes are caller-driven; the default live path records on every
 * router match, eval records only when explicitly asked to.</p>
 */
public final class SkillUsageStore {

    public enum Source { LIVE, EVAL }

    public record Usage(int hits, int liveHits, int evalHits, long lastHitEpochMillis) {
    }

    private final Path file;
    private final JSONObject root;
    private boolean dirty;

    public SkillUsageStore(Path file) {
        this.file = file;
        this.root = load(file);
    }

    private static JSONObject load(Path file) {
        if (!Files.exists(file)) {
            return new JSONObject().put("version", 1).put("skills", new JSONObject());
        }
        try {
            JSONObject json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            if (!json.has("skills")) {
                json.put("skills", new JSONObject());
            }
            return json;
        } catch (Exception e) {
            // corrupted telemetry degrades to a fresh ledger — it is statistics,
            // never behavior; nothing here may take the agent down
            return new JSONObject().put("version", 1).put("skills", new JSONObject());
        }
    }

    public synchronized void recordHit(String skillId, Source source) {
        JSONObject skills = root.optJSONObject("skills");
        if (skills == null) {
            skills = new JSONObject();
            root.put("skills", skills);
        }
        JSONObject entry = skills.optJSONObject(skillId);
        if (entry == null) {
            entry = new JSONObject();
            entry.put("hits", 0);
            entry.put("liveHits", 0);
            entry.put("evalHits", 0);
            entry.put("lastHitEpochMillis", 0L);
        }
        entry.put("hits", entry.optInt("hits", 0) + 1);
        entry.put(source == Source.LIVE ? "liveHits" : "evalHits",
                entry.optInt(source == Source.LIVE ? "liveHits" : "evalHits", 0) + 1);
        entry.put("lastHitEpochMillis", System.currentTimeMillis());
        skills.put(skillId, entry);
        dirty = true;
    }

    public Usage usage(String skillId) {
        JSONObject entry = root.optJSONObject("skills") == null
                ? null : root.optJSONObject("skills").optJSONObject(skillId);
        if (entry == null) {
            return new Usage(0, 0, 0, 0L);
        }
        return new Usage(entry.optInt("hits", 0), entry.optInt("liveHits", 0),
                entry.optInt("evalHits", 0), entry.optLong("lastHitEpochMillis", 0L));
    }

    public synchronized void flush() throws IOException {
        if (!dirty) {
            return;
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, root.toString(2), StandardCharsets.UTF_8);
        dirty = false;
    }
}
