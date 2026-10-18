package com.cifa.trace;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.List;

public record TraceEvent(
        String taskId,
        long sequence,
        Instant timestamp,
        String eventType,
        int round,
        String model,
        String promptVersion,
        String hypothesis,
        String toolName,
        String argumentsSummary,
        List<String> evidenceIds,
        int tokenCount,
        long elapsedMillis,
        String status,
        String stopReason,
        String error
) {
    private static final int MAX_TEXT = 2000;

    public TraceEvent {
        taskId = taskId == null ? "" : taskId;
        timestamp = timestamp == null ? Instant.now() : timestamp;
        eventType = eventType == null ? "event" : eventType;
        model = model == null ? "" : model;
        promptVersion = promptVersion == null ? "" : promptVersion;
        hypothesis = hypothesis == null ? "" : hypothesis;
        toolName = toolName == null ? "" : toolName;
        argumentsSummary = argumentsSummary == null ? "" : argumentsSummary;
        evidenceIds = List.copyOf(evidenceIds);
        status = status == null ? "" : status;
        stopReason = stopReason == null ? "" : stopReason;
        error = error == null ? "" : error;
    }

    public static TraceEvent of(String taskId, long sequence, String eventType) {
        return new TraceEvent(taskId, sequence, Instant.now(), eventType, 0, "", "", "", "", "",
                List.of(), 0, 0, "", "", "");
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("taskId", taskId);
        json.put("sequence", sequence);
        json.put("timestamp", timestamp.toString());
        json.put("eventType", eventType);
        json.put("round", round);
        json.put("model", safe(model));
        json.put("promptVersion", safe(promptVersion));
        json.put("hypothesis", safe(hypothesis));
        json.put("toolName", safe(toolName));
        json.put("argumentsSummary", safe(argumentsSummary));
        json.put("evidenceIds", new JSONArray(evidenceIds));
        json.put("tokenCount", tokenCount);
        json.put("elapsedMillis", elapsedMillis);
        json.put("status", safe(status));
        json.put("stopReason", safe(stopReason));
        json.put("error", safe(error));
        return json;
    }

    /** Redaction + truncation shared by JSONL and the P1 database persistence. */
    public static String safe(String value) {
        String sanitized = value.replaceAll("(?i)(api[_-]?key|token|password|secret|authorization)(\\s*[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,}\\\"']+", "$1$2[REDACTED]");
        if (sanitized.length() > MAX_TEXT) {
            return sanitized.substring(0, MAX_TEXT) + "...[truncated]";
        }
        return sanitized;
    }
}
