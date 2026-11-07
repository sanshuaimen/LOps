package com.cifa.evolution;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Behavior version control (the plan's rollback mechanism, SAGE's pattern
 * minus the online-canary machinery this project honestly lacks): every gate
 * promotion binds an immutable snapshot — the exact skills.json bytes, their
 * SHA-256, the eval report that justified them, and the dataset version —
 * into an append-only JSONL ledger, with the bytes kept as a blob beside it.
 * Rollback restores THOSE bytes verbatim: behavior goes back byte-identical,
 * and the audit log records the move.
 *
 * <p>Git already versions the file; what git cannot do is bind "this file
 * state" to "these eval scores" — the snapshot ledger is that binding, which
 * is what makes "revert to the last known-good behavior" a one-liner.</p>
 */
public final class EvolutionSnapshots {

    public record Snapshot(long seq, Instant at, String skillsHash, String evalReportRef,
                           String datasetVersion, String note, String actor) {
    }

    private final Path ledgerFile;
    private final Path blobDir;
    private final SkillAuditLog audit;

    public EvolutionSnapshots(Path ledgerFile, Path blobDir, SkillAuditLog audit) {
        this.ledgerFile = ledgerFile;
        this.blobDir = blobDir;
        this.audit = audit;
    }

    /** Snapshots the CURRENT skills.json bytes; identical bytes deduplicate by
     * hash (the blob is written once, ledger lines accumulate per binding). */
    public Snapshot snapshot(Path skillsFile, String evalReportRef, String datasetVersion,
                             String note, String actor) throws IOException {
        byte[] bytes = Files.readAllBytes(skillsFile);
        String hash = sha256(bytes);
        Files.createDirectories(blobDir);
        Path blobPath = blobDir.resolve("skills-" + hash + ".json");
        if (!Files.exists(blobPath)) {
            Files.write(blobPath, bytes);
        }
        long seq = countEntries() + 1;
        Snapshot snapshot = new Snapshot(seq, Instant.now(), hash,
                evalReportRef == null ? "" : evalReportRef,
                datasetVersion == null ? "" : datasetVersion,
                note == null ? "" : note, actor);
        JSONObject json = new JSONObject();
        json.put("seq", seq);
        json.put("at", snapshot.at().toString());
        json.put("skillsHash", hash);
        json.put("evalReportRef", snapshot.evalReportRef());
        json.put("datasetVersion", snapshot.datasetVersion());
        json.put("note", snapshot.note());
        json.put("actor", actor);
        json.put("blob", blobPath.toString());
        Files.writeString(ledgerFile, json.toString() + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        audit.append(actor, SkillAuditLog.SNAPSHOT, "", "", "", note,
                "hash=" + hash + (evalReportRef == null ? "" : " report=" + evalReportRef));
        return snapshot;
    }

    public List<Snapshot> list() throws IOException {
        List<Snapshot> snapshots = new ArrayList<>();
        if (!Files.exists(ledgerFile)) {
            return snapshots;
        }
        for (String line : Files.readAllLines(ledgerFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JSONObject json = new JSONObject(line);
                snapshots.add(new Snapshot(json.optLong("seq"), Instant.parse(json.optString("at")),
                        json.optString("skillsHash"), json.optString("evalReportRef", ""),
                        json.optString("datasetVersion", ""), json.optString("note", ""),
                        json.optString("actor", "")));
            } catch (Exception e) {
                break; // torn tail
            }
        }
        return snapshots;
    }

    /** Restores the snapshot's EXACT bytes over the live skills.json. Byte-identical
     * is the contract — pinned by an invariant test. */
    public String rollback(Path skillsFile, long seq, String actor) throws IOException {
        Snapshot target = list().stream()
                .filter(snapshot -> snapshot.seq() == seq)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no snapshot with seq " + seq));
        Path blob = blobDir.resolve("skills-" + target.skillsHash() + ".json");
        if (!Files.exists(blob)) {
            throw new IOException("snapshot blob missing: " + blob + " — the ledger survives but the bytes are gone");
        }
        byte[] bytes = Files.readAllBytes(blob);
        Files.write(skillsFile, bytes);
        audit.append(actor, SkillAuditLog.ROLLBACK, "", "", "",
                "restored snapshot " + seq + " (hash " + target.skillsHash() + ")",
                target.evalReportRef());
        return target.skillsHash();
    }

    private long countEntries() throws IOException {
        return list().size();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes), 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
