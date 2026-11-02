package com.cifa.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * The final structured output of an investigation. {@code unknown} marks an
 * honest "insufficient evidence" outcome; an unknown diagnosis never carries
 * a suggested action. {@code judgeVerdict} is the semantic-review stamp
 * (layer 3): blank when no review ran, the {@link JudgmentAgent.Verdict}
 * name otherwise — a stamp only, never a recomputed confidence.
 */
public record StructuredDiagnosis(
        String category,
        String rootCause,
        List<String> evidenceIds,
        int confidence,
        String suggestedAction,
        boolean unknown,
        AgentStopReason stopReason,
        String judgeVerdict
) {
    public StructuredDiagnosis {
        category = category == null || category.isBlank() ? "UNKNOWN" : category;
        rootCause = rootCause == null ? "" : rootCause;
        evidenceIds = List.copyOf(evidenceIds);
        confidence = Math.max(0, Math.min(100, confidence));
        suggestedAction = unknown ? "" : suggestedAction == null ? "" : suggestedAction;
        judgeVerdict = judgeVerdict == null ? "" : judgeVerdict;
        if (stopReason == null) {
            throw new IllegalArgumentException("stop reason is required");
        }
    }

    /** Pre-judge signature (no semantic review ran): stamp stays blank. */
    public StructuredDiagnosis(String category, String rootCause, List<String> evidenceIds, int confidence,
                               String suggestedAction, boolean unknown, AgentStopReason stopReason) {
        this(category, rootCause, evidenceIds, confidence, suggestedAction, unknown, stopReason, "");
    }

    public static StructuredDiagnosis unknown(AgentStopReason stopReason, String reason, List<String> evidenceIds) {
        return new StructuredDiagnosis("UNKNOWN", reason, evidenceIds, 0, "", true, stopReason);
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject()
                .put("category", category)
                .put("rootCause", rootCause)
                .put("evidenceIds", new JSONArray(evidenceIds))
                .put("confidence", confidence)
                .put("suggestedAction", suggestedAction)
                .put("unknown", unknown)
                .put("stopReason", stopReason.name());
        if (!judgeVerdict.isBlank()) {
            json.put("judgeVerdict", judgeVerdict);
        }
        return json;
    }
}
