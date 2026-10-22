package com.cifa.memory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * One "错题本" entry: a past failure the agent got wrong (or that was confirmed
 * interesting), stored so similar future failures can retrieve it as a reference
 * example. Pure data — the injection site treats entries as EVIDENCE ONLY.
 *
 * <p>Leakage rule (plans.md §八): entries must never be created from the eval
 * accept layer; {@code caseId} is retained so retrieval can exclude the very
 * case that produced an entry.
 */
public record FailureMemoryEntry(
        String id,
        String caseId,
        String layer,
        String logSignature,
        String exceptionType,
        List<String> keywords,
        String category,
        String rootCauseSummary,
        List<String> evidenceHints,
        String remediation,
        String source) {

    public FailureMemoryEntry {
        keywords = List.copyOf(keywords);
        evidenceHints = List.copyOf(evidenceHints);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("memory entry id is required");
        }
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("memory entry caseId is required");
        }
    }

    /** Token stream for retrieval: signature words, exception type, keywords, category. */
    public List<String> retrievalTokens() {
        String combined = logSignature + " " + exceptionType + " " + category + " "
                + String.join(" ", keywords) + " " + String.join(" ", evidenceHints);
        return FailureMemoryRetrieval.tokenize(combined);
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("caseId", caseId);
        json.put("layer", layer);
        json.put("logSignature", logSignature);
        json.put("exceptionType", exceptionType);
        json.put("keywords", new JSONArray(keywords));
        json.put("category", category);
        json.put("rootCauseSummary", rootCauseSummary);
        json.put("evidenceHints", new JSONArray(evidenceHints));
        json.put("remediation", remediation);
        json.put("source", source);
        return json;
    }

    public static FailureMemoryEntry fromJson(JSONObject json) {
        JSONArray keywords = json.optJSONArray("keywords");
        JSONArray hints = json.optJSONArray("evidenceHints");
        return new FailureMemoryEntry(
                json.optString("id"),
                json.optString("caseId"),
                json.optString("layer", ""),
                json.optString("logSignature", ""),
                json.optString("exceptionType", ""),
                strings(keywords),
                json.optString("category", ""),
                json.optString("rootCauseSummary", ""),
                strings(hints),
                json.optString("remediation", ""),
                json.optString("source", "golden-confirmed"));
    }

    private static List<String> strings(JSONArray array) {
        if (array == null) {
            return List.of();
        }
        java.util.List<String> values = new java.util.ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            values.add(array.getString(i));
        }
        return values;
    }
}
