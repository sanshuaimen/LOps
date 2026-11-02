package com.cifa.memory;

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
 * Local-file store for the failure memory ("错题本"). One JSON document, same
 * style as the fixture corpus. Writes are explicit and caller-driven — the eval
 * pipeline never touches this class (guarded by an ArchUnit rule), which is the
 * mechanical half of the leakage prevention protocol.
 */
public final class FailureMemoryStore {

    /** Layers whose failures may seed the memory. The accept layer is the exam —
     * its cases (and goldens) must never land in the notebook. "distilled" marks
     * entries produced by the MemoryDistiller from a completed real diagnosis —
     * not eval data, so the exam-exclusion concern does not apply. */
    public static final List<String> ALLOWED_SOURCE_LAYERS =
            List.of("regression", "robustness", "distilled");

    private final Path file;
    private final List<FailureMemoryEntry> entries;

    public FailureMemoryStore(Path file) {
        this.file = file;
        this.entries = load(file);
    }

    private static List<FailureMemoryEntry> load(Path file) {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            JSONObject json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            JSONArray array = json.optJSONArray("entries");
            List<FailureMemoryEntry> loaded = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    loaded.add(FailureMemoryEntry.fromJson(array.getJSONObject(i)));
                }
            }
            return loaded;
        } catch (Exception e) {
            // A corrupted notebook degrades to empty (and is rewritten on the next
            // append) rather than blocking the agent — same posture as fixtures.
            return new ArrayList<>();
        }
    }

    public List<FailureMemoryEntry> entries() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    public Optional<FailureMemoryEntry> byId(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    /** Appends and persists. Refuses accept-layer sources: the exam never enters
     * the notebook. Duplicate ids are rejected — entries are immutable facts. */
    public FailureMemoryEntry append(FailureMemoryEntry entry) throws IOException {
        if (!ALLOWED_SOURCE_LAYERS.contains(entry.layer())) {
            throw new IllegalArgumentException("layer '" + entry.layer()
                    + "' may not seed the failure memory (allowed: " + ALLOWED_SOURCE_LAYERS
                    + "; the accept layer is the held-out exam)");
        }
        if (byId(entry.id()).isPresent()) {
            throw new IllegalArgumentException("duplicate memory entry id: " + entry.id());
        }
        entries.add(entry);
        write();
        return entry;
    }

    /** Replaces an existing entry in place (same id) and persists. Used by the
     * consolidation path: merging new evidence into an existing lesson must not
     * change its identity. Layer and existence are re-checked — a replacement
     * may neither smuggle in a forbidden layer nor invent an id. */
    public FailureMemoryEntry update(FailureMemoryEntry replacement) throws IOException {
        if (!ALLOWED_SOURCE_LAYERS.contains(replacement.layer())) {
            throw new IllegalArgumentException("layer '" + replacement.layer()
                    + "' may not seed the failure memory (allowed: " + ALLOWED_SOURCE_LAYERS + ")");
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(replacement.id())) {
                entries.set(i, replacement);
                write();
                return replacement;
            }
        }
        throw new IllegalArgumentException("no memory entry with id: " + replacement.id());
    }

    private void write() throws IOException {
        JSONObject json = new JSONObject();
        json.put("version", 1);
        JSONArray array = new JSONArray();
        entries.forEach(entry -> array.put(entry.toJson()));
        json.put("entries", array);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, json.toString(2), StandardCharsets.UTF_8);
    }
}
