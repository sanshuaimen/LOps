package com.cifa.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * The final structured output of an investigation. {@code unknown} marks an
 * honest "insufficient evidence" outcome; an unknown diagnosis never carries
 * a suggested action.
 */
public record StructuredDiagnosis(
        String category,
        String rootCause,
        List<String> evidenceIds,
        int confidence,
        String suggestedAction,
        boolean unknown,
        AgentStopReason stopReason
) {
    public StructuredDiagnosis {
        category = category == null || category.isBlank() ? "UNKNOWN" : category;
        rootCause = rootCause == null ? "" : rootCause;
        evidenceIds = List.copyOf(evidenceIds);
        confidence = Math.max(0, Math.min(100, confidence));
        suggestedAction = unknown ? "" : suggestedAction == null ? "" : suggestedAction;
        if (stopReason == null) {
            throw new IllegalArgumentException("stop reason is required");
        }
    }

    public static StructuredDiagnosis unknown(AgentStopReason stopReason, String reason, List<String> evidenceIds) {
        return new StructuredDiagnosis("UNKNOWN", reason, evidenceIds, 0, "", true, stopReason);
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("category", category)
                .put("rootCause", rootCause)
                .put("evidenceIds", new JSONArray(evidenceIds))
                .put("confidence", confidence)
                .put("suggestedAction", suggestedAction)
                .put("unknown", unknown)
                .put("stopReason", stopReason.name());
    }
}
