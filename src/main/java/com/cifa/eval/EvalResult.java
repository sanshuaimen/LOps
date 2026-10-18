package com.cifa.eval;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public record EvalResult(
        String caseId,
        String expectedCategory,
        String actualCategory,
        boolean categoryCorrect,
        boolean rootCauseCorrect,
        boolean evidenceCorrect,
        boolean evidenceSufficient,
        boolean unknownCorrect,
        boolean forbiddenConclusionFree,
        boolean requiredToolsSatisfied,
        List<String> toolCalls,
        boolean fullSweep,
        int irrelevantToolCalls,
        int repeatedToolCalls,
        int tokenCount,
        long elapsedMillis,
        String stopReason,
        String error
) {
    public EvalResult {
        toolCalls = List.copyOf(toolCalls);
        stopReason = stopReason == null ? "" : stopReason;
        error = error == null ? "" : error;
    }

    public boolean passed() {
        return error.isEmpty() && categoryCorrect && rootCauseCorrect && evidenceCorrect
                && evidenceSufficient && unknownCorrect && forbiddenConclusionFree
                && requiredToolsSatisfied;
    }

    public double irrelevantToolCallRate() {
        return toolCalls.isEmpty() ? 0.0 : (double) irrelevantToolCalls / toolCalls.size();
    }

    public double invalidRepeatRate() {
        return toolCalls.isEmpty() ? 0.0 : (double) repeatedToolCalls / toolCalls.size();
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("caseId", caseId);
        json.put("expectedCategory", expectedCategory);
        json.put("actualCategory", actualCategory);
        json.put("categoryCorrect", categoryCorrect);
        json.put("rootCauseCorrect", rootCauseCorrect);
        json.put("evidenceCorrect", evidenceCorrect);
        json.put("evidenceSufficient", evidenceSufficient);
        json.put("unknownCorrect", unknownCorrect);
        json.put("forbiddenConclusionFree", forbiddenConclusionFree);
        json.put("requiredToolsSatisfied", requiredToolsSatisfied);
        json.put("passed", passed());
        json.put("toolCalls", new JSONArray(toolCalls));
        json.put("fullSweep", fullSweep);
        json.put("irrelevantToolCalls", irrelevantToolCalls);
        json.put("repeatedToolCalls", repeatedToolCalls);
        json.put("tokenCount", tokenCount);
        json.put("elapsedMillis", elapsedMillis);
        json.put("stopReason", stopReason);
        json.put("error", error);
        return json;
    }
}
