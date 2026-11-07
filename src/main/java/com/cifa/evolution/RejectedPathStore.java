package com.cifa.evolution;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The negative-knowledge ledger ("什么路走过但走不通"): every rejected skill
 * candidate, every gate refusal, and every distilled dead end lands here, so
 * the next proposal round can SEE the graveyard instead of re-walking it.
 * The plan's Module-A insight verbatim: "被拒绝的修改本身也是知识".
 *
 * <p>Append-only, deduplicated on (signature, approach) exact match — the
 * same dead end proposed twice is one entry with a repeated count, not two.
 * Corruption degrades to empty: this file steers proposals, it never gates
 * behavior.</p>
 */
public final class RejectedPathStore {

    public record RejectedPath(String signature, String approach, String whyFailed,
                               String sourceTrace, String datasetVersion, int timesSeen) {
    }

    private final Path file;
    private final List<RejectedPath> paths;

    public RejectedPathStore(Path file) {
        this.file = file;
        this.paths = load(file);
    }

    private static List<RejectedPath> load(Path file) {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            JSONObject json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            JSONArray array = json.optJSONArray("paths");
            List<RejectedPath> loaded = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject entry = array.getJSONObject(i);
                    loaded.add(new RejectedPath(
                            entry.optString("signature", ""),
                            entry.optString("approach", ""),
                            entry.optString("whyFailed", ""),
                            entry.optString("sourceTrace", ""),
                            entry.optString("datasetVersion", ""),
                            Math.max(1, entry.optInt("timesSeen", 1))));
                }
            }
            return loaded;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public List<RejectedPath> all() {
        return List.copyOf(paths);
    }

    public int size() {
        return paths.size();
    }

    /** Appends; an exact (signature, approach) repeat increments timesSeen instead
     * of adding a line — the ledger records "tried AGAIN and failed AGAIN". */
    public RejectedPath append(RejectedPath path) throws IOException {
        for (int i = 0; i < paths.size(); i++) {
            RejectedPath existing = paths.get(i);
            if (existing.signature().equals(path.signature()) && existing.approach().equals(path.approach())) {
                RejectedPath bumped = new RejectedPath(existing.signature(), existing.approach(),
                        path.whyFailed().isBlank() ? existing.whyFailed() : path.whyFailed(),
                        path.sourceTrace().isBlank() ? existing.sourceTrace() : path.sourceTrace(),
                        path.datasetVersion().isBlank() ? existing.datasetVersion() : path.datasetVersion(),
                        existing.timesSeen() + 1);
                paths.set(i, bumped);
                write();
                return bumped;
            }
        }
        RejectedPath stored = new RejectedPath(path.signature(), path.approach(), path.whyFailed(),
                path.sourceTrace(), path.datasetVersion(), 1);
        paths.add(stored);
        write();
        return stored;
    }

    /** Deterministic relevance: count of signature keywords present in the
     * query text, top-k, capped char budget — what the proposer prompt shows. */
    public List<RejectedPath> relevantFor(String signatureText, int k) {
        String query = signatureText == null ? "" : signatureText.toLowerCase(java.util.Locale.ROOT);
        record Scored(RejectedPath path, long score) {
        }
        return paths.stream()
                .map(path -> {
                    String signature = path.signature().toLowerCase(java.util.Locale.ROOT);
                    long score = java.util.Arrays.stream(signature.split("\\s+"))
                            .filter(token -> !token.isBlank() && query.contains(token))
                            .count();
                    return new Scored(path, score);
                })
                .filter(scored -> scored.score() > 0)
                .sorted((a, b) -> Long.compare(b.score(), a.score()))
                .limit(Math.max(0, k))
                .map(Scored::path)
                .toList();
    }

    private void write() throws IOException {
        JSONObject json = new JSONObject();
        json.put("version", 1);
        JSONArray array = new JSONArray();
        paths.forEach(path -> {
            JSONObject entry = new JSONObject();
            entry.put("signature", path.signature());
            entry.put("approach", path.approach());
            entry.put("whyFailed", path.whyFailed());
            entry.put("sourceTrace", path.sourceTrace());
            entry.put("datasetVersion", path.datasetVersion());
            entry.put("timesSeen", path.timesSeen());
            array.put(entry);
        });
        json.put("paths", array);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, json.toString(2), StandardCharsets.UTF_8);
    }
}
