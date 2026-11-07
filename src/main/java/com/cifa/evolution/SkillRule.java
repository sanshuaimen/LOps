package com.cifa.evolution;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * One behavior asset of the evolution system: a validated-experience rule in
 * declarative, diffable, revertible form (the plan's core decision — skills
 * are DATA, so merging is a file change, rollback is a byte restore, and the
 * audit trail is the file's history).
 *
 * <p>Every rule carries its full provenance chain: which trace produced it
 * ({@code sourceTrace}), which cases backed it ({@code caseIds}), which
 * evidence anchored it ({@code evidenceHints}), and what the paired gate
 * measured before it was allowed to change behavior ({@code wins}/{@code
 * losses}/{@code validatedOnDataset}). A skill without provenance is noise;
 * the store refuses to persist one.</p>
 *
 * <p>Match keys are deterministic on purpose: {@code category} (a
 * {@link com.cifa.classification.FailureCategory} name, or ANY) plus
 * {@code symptomKeywords} are matched by {@link SkillsRouter} in plain code —
 * the model never chooses which skill applies (deterministic disclosure,
 * per the plan's "matching authority stays in the deterministic layer").</p>
 */
public record SkillRule(
        String id,
        String name,
        SkillKind kind,
        String category,
        List<String> symptomKeywords,
        List<String> routingTools,
        String instruction,
        SkillStatus status,
        String supersededBy,
        String rejectionReason,
        String sourceTrace,
        List<String> caseIds,
        List<String> evidenceHints,
        String validatedOnDataset,
        int wins,
        int losses,
        String promotedBy) {

    /** Category wildcard: the skill applies to every failure category. */
    public static final String ANY_CATEGORY = "ANY";

    private static final int MAX_KEYWORDS = 8;
    private static final int MAX_KEYWORD_CHARS = 48;
    private static final int MAX_INSTRUCTION_CHARS = 600;
    private static final int MAX_NAME_CHARS = 80;

    public SkillRule {
        symptomKeywords = List.copyOf(symptomKeywords);
        routingTools = List.copyOf(routingTools);
        caseIds = List.copyOf(caseIds);
        evidenceHints = List.copyOf(evidenceHints);
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        category = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        instruction = instruction == null ? "" : instruction.trim();
        supersededBy = supersededBy == null ? "" : supersededBy.trim();
        rejectionReason = rejectionReason == null ? "" : rejectionReason.trim();
        sourceTrace = sourceTrace == null ? "" : sourceTrace.trim();
        validatedOnDataset = validatedOnDataset == null ? "" : validatedOnDataset.trim();
        promotedBy = promotedBy == null ? "" : promotedBy.trim();
        if (id.isBlank()) {
            throw new IllegalArgumentException("skill id is required");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("skill name is required");
        }
        if (category.isBlank()) {
            throw new IllegalArgumentException("skill category is required (a FailureCategory name or ANY)");
        }
        if (symptomKeywords.isEmpty()) {
            throw new IllegalArgumentException("skill '" + id + "' needs at least one symptom keyword to match on");
        }
        switch (kind) {
            case ROUTING -> {
                if (routingTools.isEmpty()) {
                    throw new IllegalArgumentException("ROUTING skill '" + id + "' needs a non-empty routingTools order");
                }
                if (!instruction.isBlank()) {
                    throw new IllegalArgumentException("ROUTING skill '" + id + "' must not carry prompt text; "
                            + "routing compiles into the deterministic layer, not the prompt");
                }
            }
            case PROMPT -> {
                if (instruction.isBlank()) {
                    throw new IllegalArgumentException("PROMPT skill '" + id + "' needs an instruction fragment");
                }
                if (!routingTools.isEmpty()) {
                    throw new IllegalArgumentException("PROMPT skill '" + id + "' must not carry routingTools");
                }
                if (instruction.length() > MAX_INSTRUCTION_CHARS) {
                    throw new IllegalArgumentException("PROMPT skill '" + id + "' instruction exceeds "
                            + MAX_INSTRUCTION_CHARS + " chars — keep skills minimal, context is a budget");
                }
            }
        }
        if (name.length() > MAX_NAME_CHARS) {
            throw new IllegalArgumentException("skill name exceeds " + MAX_NAME_CHARS + " chars");
        }
        if (symptomKeywords.size() > MAX_KEYWORDS) {
            throw new IllegalArgumentException("skill '" + id + "' exceeds " + MAX_KEYWORDS + " symptom keywords");
        }
    }

    /** Stable content address: identical advice deduplicates by construction,
     * mirroring {@link com.cifa.memory.MemoryDistiller}'s entry ids. */
    public static String newId(SkillKind kind, String category, List<String> symptomKeywords, String advice) {
        String canonical = kind + "|" + category.toUpperCase(Locale.ROOT) + "|"
                + String.join(",", symptomKeywords) + "|" + advice.strip();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("skill-");
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public boolean isActive() {
        return status == SkillStatus.ACTIVE;
    }

    public boolean matchesCategory(String failureCategory) {
        return category.equals(ANY_CATEGORY) || category.equals(failureCategory.toUpperCase(Locale.ROOT));
    }

    /** Deterministic symptom match: at least one keyword occurs in the failure
     * signature text. Plain substring, lowercase — same posture as
     * {@code MemoryCommand.firstExceptionLine}. */
    public boolean matchesSymptoms(String signatureText) {
        String haystack = signatureText == null ? "" : signatureText.toLowerCase(Locale.ROOT);
        return symptomKeywords.stream()
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .anyMatch(haystack::contains);
    }

    public SkillRule withStatus(SkillStatus newStatus) {
        return new SkillRule(id, name, kind, category, symptomKeywords, routingTools, instruction,
                newStatus, supersededBy, rejectionReason, sourceTrace, caseIds, evidenceHints,
                validatedOnDataset, wins, losses, promotedBy);
    }

    public SkillRule supersededBy(String successorId) {
        return new SkillRule(id, name, kind, category, symptomKeywords, routingTools, instruction,
                SkillStatus.SUPERSEDED, successorId, rejectionReason, sourceTrace, caseIds, evidenceHints,
                validatedOnDataset, wins, losses, promotedBy);
    }

    public SkillRule withRejection(String reason) {
        return new SkillRule(id, name, kind, category, symptomKeywords, routingTools, instruction,
                status, supersededBy, reason, sourceTrace, caseIds, evidenceHints,
                validatedOnDataset, wins, losses, promotedBy);
    }

    public SkillRule withValidation(String datasetVersion, int wins, int losses) {
        return new SkillRule(id, name, kind, category, symptomKeywords, routingTools, instruction,
                status, supersededBy, rejectionReason, sourceTrace, caseIds, evidenceHints,
                datasetVersion, wins, losses, promotedBy);
    }

    public SkillRule promotedBy(String approver) {
        return new SkillRule(id, name, kind, category, symptomKeywords, routingTools, instruction,
                status, supersededBy, rejectionReason, sourceTrace, caseIds, evidenceHints,
                validatedOnDataset, wins, losses, approver);
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("kind", kind.name());
        json.put("category", category);
        json.put("symptomKeywords", new JSONArray(symptomKeywords));
        json.put("routingTools", new JSONArray(routingTools));
        json.put("instruction", instruction);
        json.put("status", status.name());
        if (!supersededBy.isBlank()) {
            json.put("supersededBy", supersededBy);
        }
        if (!rejectionReason.isBlank()) {
            json.put("rejectionReason", rejectionReason);
        }
        json.put("sourceTrace", sourceTrace);
        json.put("caseIds", new JSONArray(caseIds));
        json.put("evidenceHints", new JSONArray(evidenceHints));
        if (!validatedOnDataset.isBlank()) {
            json.put("validatedOnDataset", validatedOnDataset);
            json.put("wins", wins);
            json.put("losses", losses);
        }
        if (!promotedBy.isBlank()) {
            json.put("promotedBy", promotedBy);
        }
        return json;
    }

    public static SkillRule fromJson(JSONObject json) {
        return new SkillRule(
                json.optString("id"),
                json.optString("name"),
                SkillKind.valueOf(json.optString("kind", "PROMPT")),
                json.optString("category"),
                strings(json.optJSONArray("symptomKeywords")),
                strings(json.optJSONArray("routingTools")),
                json.optString("instruction", ""),
                SkillStatus.valueOf(json.optString("status", "CANDIDATE")),
                json.optString("supersededBy", ""),
                json.optString("rejectionReason", ""),
                json.optString("sourceTrace", ""),
                strings(json.optJSONArray("caseIds")),
                strings(json.optJSONArray("evidenceHints")),
                json.optString("validatedOnDataset", ""),
                json.optInt("wins", 0),
                json.optInt("losses", 0),
                json.optString("promotedBy", ""));
    }

    private static List<String> strings(JSONArray array) {
        if (array == null) {
            return List.of();
        }
        List<String> values = new java.util.ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            values.add(array.getString(i));
        }
        return values;
    }
}
