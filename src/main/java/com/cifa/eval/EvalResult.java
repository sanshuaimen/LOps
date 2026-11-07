package com.cifa.eval;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public record EvalResult(
        String caseId,
        String expectedCategory,
        String actualCategory,
        String layer,
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
        json.put("layer", layer);
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

    /** Mirrors {@link #toJson()}: the paired gate (evolution) reloads stored
     * reports to compare baseline vs candidate on the same cases. */
    public static EvalResult fromJson(JSONObject json) {
        JSONArray calls = json.optJSONArray("toolCalls");
        List<String> toolCalls = new ArrayList<>();
        if (calls != null) {
            for (int i = 0; i < calls.length(); i++) {
                toolCalls.add(calls.getString(i));
            }
        }
        return new EvalResult(
                json.optString("caseId"),
                json.optString("expectedCategory"),
                json.optString("actualCategory"),
                json.optString("layer"),
                json.optBoolean("categoryCorrect"),
                json.optBoolean("rootCauseCorrect"),
                json.optBoolean("evidenceCorrect"),
                json.optBoolean("evidenceSufficient"),
                json.optBoolean("unknownCorrect"),
                json.optBoolean("forbiddenConclusionFree"),
                json.optBoolean("requiredToolsSatisfied"),
                toolCalls,
                json.optBoolean("fullSweep"),
                json.optInt("irrelevantToolCalls", 0),
                json.optInt("repeatedToolCalls", 0),
                json.optInt("tokenCount", 0),
                json.optLong("elapsedMillis", 0L),
                json.optString("stopReason"),
                json.optString("error"));
    }
}
