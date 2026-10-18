package com.cifa.eval;

import java.util.List;

public record EvalObservation(
        String category,
        String rootCause,
        List<String> evidence,
        String suggestedAction,
        boolean unknown,
        List<String> toolCalls,
        List<String> toolCallSignatures,
        int availableToolCount,
        int tokenCount,
        long elapsedMillis,
        String stopReason,
        String error
) {
    public EvalObservation {
        evidence = List.copyOf(evidence);
        toolCalls = List.copyOf(toolCalls);
        toolCallSignatures = toolCallSignatures == null ? List.of() : List.copyOf(toolCallSignatures);
        rootCause = rootCause == null ? "" : rootCause;
        suggestedAction = suggestedAction == null ? "" : suggestedAction;
        category = category == null ? "UNKNOWN" : category;
        stopReason = stopReason == null ? "" : stopReason;
        error = error == null ? "" : error;
    }

    public static EvalObservation failure(long elapsedMillis, String error) {
        return new EvalObservation("UNKNOWN", "", List.of(), "", true,
                List.of(), List.of(), 0, 0, elapsedMillis, "FAILED", error);
    }
}
