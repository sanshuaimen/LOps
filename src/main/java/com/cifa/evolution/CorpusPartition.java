package com.cifa.evolution;

import com.cifa.eval.GoldenCase;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The plan-correction answer to "the gate eats the exam": the golden corpus is
 * split ONCE, deterministically, into
 *
 * <ul>
 *   <li><b>HOLDOUT</b> — a fixed stratified slice (~30%) that is DOUBLE-BLIND:
 *       it never reaches the Proposer's visible inputs (harvest refuses traces
 *       sourced from holdout cases) and never reaches merge decisions (the
 *       paired gate refuses reports containing holdout cases). It exists for
 *       post-hoc monitoring only — "how does the current behavior set do on
 *       cases no evolution decision ever touched".</li>
 *   <li><b>PROPOSAL</b> — the rotating gate set where Proposer and PairedGatekeeper
 *       operate, replenished as the injection harness grows the corpus.</li>
 * </ul>
 *
 * <p>Assignment is a keyed hash bucket per (seed, layer, caseId) — stable
 * across JVMs and reruns, stratified per layer so tiny layers (security)
 * partition sanely. The partition file pins both id lists and participates in
 * the corpus provenance chain.</p>
 */
public final class CorpusPartition {

    public static final String DEFAULT_SEED = "cifa-holdout-v1";
    public static final double DEFAULT_HOLDOUT_FRACTION = 0.30;

    private final String seed;
    private final double holdoutFraction;
    private final Set<String> holdoutIds;
    private final Set<String> proposalIds;

    private CorpusPartition(String seed, double holdoutFraction,
                            Set<String> holdoutIds, Set<String> proposalIds) {
        this.seed = seed;
        this.holdoutFraction = holdoutFraction;
        this.holdoutIds = Set.copyOf(holdoutIds);
        this.proposalIds = Set.copyOf(proposalIds);
    }

    /** Deterministic stratified split over the loaded corpus. */
    public static CorpusPartition build(List<GoldenCase> cases, double holdoutFraction, String seed) {
        if (holdoutFraction <= 0.0 || holdoutFraction >= 1.0) {
            throw new IllegalArgumentException("holdoutFraction must be within (0,1), got " + holdoutFraction);
        }
        Set<String> holdout = new LinkedHashSet<>();
        Set<String> proposal = new LinkedHashSet<>();
        // stratify per layer, then stable order inside the layer
        List<String> layers = cases.stream().map(GoldenCase::layer).distinct().sorted().toList();
        for (String layer : layers) {
            List<GoldenCase> inLayer = cases.stream()
                    .filter(c -> c.layer().equals(layer))
                    .sorted(Comparator.comparing(GoldenCase::id))
                    .toList();
            for (GoldenCase goldenCase : inLayer) {
                if (bucket(seed, layer, goldenCase.id()) < holdoutFraction) {
                    holdout.add(goldenCase.id());
                } else {
                    proposal.add(goldenCase.id());
                }
            }
        }
        return new CorpusPartition(seed, holdoutFraction, holdout, proposal);
    }

    /** First byte of SHA-256(seed|layer|caseId), normalized to [0,1). */
    static double bucket(String seed, String layer, String caseId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((seed + "|" + layer + "|" + caseId).getBytes(StandardCharsets.UTF_8));
            return (digest[0] & 0xFF) / 256.0;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public boolean isHoldout(String caseId) {
        return holdoutIds.contains(caseId);
    }

    public Set<String> holdoutIds() {
        return Set.copyOf(holdoutIds);
    }

    public Set<String> proposalIds() {
        return Set.copyOf(proposalIds);
    }

    public List<GoldenCase> proposalCases(List<GoldenCase> allCases) {
        return side(allCases, false);
    }

    public List<GoldenCase> holdoutCases(List<GoldenCase> allCases) {
        return side(allCases, true);
    }

    private List<GoldenCase> side(List<GoldenCase> allCases, boolean holdoutSide) {
        return allCases.stream()
                .filter(c -> holdoutSide == isHoldout(c.id()))
                .sorted(Comparator.comparing(GoldenCase::id))
                .toList();
    }

    /**
     * The smoke tier: a deterministic stratified prefix of the PROPOSAL set
     * (round-robin across layers so every layer is represented even when n is
     * small). Cheap pre-filter before a full-set run; never contains holdout.
     */
    public List<String> smokeIds(int n, List<GoldenCase> allCases) {
        if (n < 1) {
            throw new IllegalArgumentException("smoke size must be >= 1");
        }
        List<GoldenCase> proposal = proposalCases(allCases);
        List<String> layers = proposal.stream().map(GoldenCase::layer).distinct().sorted().toList();
        List<String> picked = new ArrayList<>();
        int cursor = 0;
        while (picked.size() < Math.min(n, proposal.size())) {
            for (String layer : layers) {
                if (picked.size() >= Math.min(n, proposal.size())) {
                    break;
                }
                List<String> idsInLayer = proposal.stream()
                        .filter(c -> c.layer().equals(layer))
                        .map(GoldenCase::id)
                        .toList();
                if (!idsInLayer.isEmpty()) {
                    picked.add(idsInLayer.get(cursor % idsInLayer.size()));
                }
            }
            cursor++;
        }
        return List.copyOf(new LinkedHashSet<>(picked));
    }

    /**
     * The double-blind guard, called by the harvest pipeline and the gate:
     * a holdout case reaching either side is a hard, loud failure — the
     * blind never erodes "by accident".
     */
    public void enforceBlind(String caseId, String where) {
        if (isHoldout(caseId)) {
            throw new IllegalStateException("holdout case '" + caseId + "' reached " + where
                    + " — the fixed holdout is double-blind (never in Proposer input, never in gate decisions)");
        }
    }

    public void save(Path file) throws IOException {
        JSONObject json = new JSONObject();
        json.put("version", 1);
        json.put("seed", seed);
        json.put("holdoutFraction", holdoutFraction);
        json.put("holdout", new JSONArray(new ArrayList<>(holdoutIds)));
        json.put("proposal", new JSONArray(new ArrayList<>(proposalIds)));
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, json.toString(2), StandardCharsets.UTF_8);
    }

    public static CorpusPartition load(Path file) throws IOException {
        JSONObject json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
        return new CorpusPartition(
                json.optString("seed", DEFAULT_SEED),
                json.optDouble("holdoutFraction", DEFAULT_HOLDOUT_FRACTION),
                new LinkedHashSet<>(strings(json.optJSONArray("holdout"))),
                new LinkedHashSet<>(strings(json.optJSONArray("proposal"))));
    }

    public String describe() {
        return "seed=" + seed + " holdout=" + holdoutIds.size() + " proposal=" + proposalIds.size();
    }

    private static List<String> strings(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                values.add(array.getString(i));
            }
        }
        return values;
    }
}
