package com.cifa.llm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AgentDecision(
        Kind kind,
        String hypothesis,
        String toolName,
        Map<String, Object> arguments,
        String category,
        String rootCause,
        List<String> evidenceIds,
        int confidence,
        String suggestedAction
) {
    public enum Kind { TOOL_CALL, SUBMIT, UNKNOWN }

    private static final int MAX_NESTING_DEPTH = 64;

    /** Linear scan, string-aware (braces inside quoted text don't count): bounds
     * recursive descent before org.json parses a single character. */
    private static int nestingDepth(String json) {
        int max = 0;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> max = Math.max(max, ++depth);
                case '}', ']' -> depth--;
                default -> { }
            }
        }
        return max;
    }

    public AgentDecision {
        hypothesis = hypothesis == null ? "" : hypothesis;
        toolName = toolName == null ? "" : toolName;
        arguments = copyArguments(arguments);
        category = category == null ? "UNKNOWN" : category;
        rootCause = rootCause == null ? "" : rootCause;
        evidenceIds = List.copyOf(evidenceIds);
        confidence = Math.max(0, Math.min(100, confidence));
        suggestedAction = suggestedAction == null ? "" : suggestedAction;
        if (kind == null) {
            throw new IllegalArgumentException("decision kind is required");
        }
        if (kind == Kind.TOOL_CALL && toolName.isBlank()) {
            throw new IllegalArgumentException("tool call requires a tool name");
        }
    }

    public static AgentDecision toolCall(String toolName, Map<String, ?> arguments, String hypothesis) {
        return new AgentDecision(Kind.TOOL_CALL, hypothesis, toolName, copyArguments(arguments),
                "UNKNOWN", "", List.of(), 0, "");
    }

    public static AgentDecision toolCall(String toolName, Map<String, ?> arguments) {
        return toolCall(toolName, arguments, "");
    }

    public static AgentDecision submit(String category, String rootCause, List<String> evidenceIds,
                                       int confidence, String suggestedAction) {
        return new AgentDecision(Kind.SUBMIT, "", "", Map.of(), category, rootCause,
                evidenceIds, confidence, suggestedAction);
    }

    public static AgentDecision submit(String rootCause, List<String> evidenceIds,
                                       int confidence, String suggestedAction) {
        return submit("UNKNOWN", rootCause, evidenceIds, confidence, suggestedAction);
    }

    public static AgentDecision unknown(String reason) {
        return unknown(reason, List.of());
    }

    public static AgentDecision unknown(String reason, List<String> evidenceIds) {
        return new AgentDecision(Kind.UNKNOWN, reason, "", Map.of(), "UNKNOWN",
                reason, evidenceIds, 0, "");
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("type", switch (kind) {
            case TOOL_CALL -> "tool_call";
            case SUBMIT -> "submit";
            case UNKNOWN -> "unknown";
        });
        if (!hypothesis.isEmpty()) {
            json.put("hypothesis", hypothesis);
        }
        if (kind == Kind.TOOL_CALL) {
            json.put("tool", toolName);
            json.put("arguments", new JSONObject(arguments));
        } else {
            json.put("category", category);
            json.put("rootCause", rootCause);
            json.put("evidenceIds", new JSONArray(evidenceIds));
            json.put("confidence", confidence);
            json.put("suggestedAction", suggestedAction);
        }
        return json;
    }

    public static AgentDecision fromJson(String text) throws JSONException {
        String trimmed = text == null ? "" : text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new JSONException("no decision JSON object found");
        }
        String candidate = trimmed.substring(start, end + 1);
        if (nestingDepth(candidate) > MAX_NESTING_DEPTH) {
            // org.json (20240303) has no depth limit: pathological model output would
            // recurse until StackOverflowError, an Error no catch in the loop can hold
            throw new JSONException("decision JSON nesting deeper than " + MAX_NESTING_DEPTH);
        }
        JSONObject json = new JSONObject(candidate);
        String type = json.optString("type", json.optString("action", "submit"))
                .toLowerCase(java.util.Locale.ROOT);
        if (type.equals("tool") || type.equals("tool_call") || type.equals("call_tool")) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            JSONObject args = json.optJSONObject("arguments");
            if (args != null) {
                for (String key : args.keySet()) {
                    arguments.put(key, args.get(key));
                }
            }
            String name = json.optString("tool", json.optString("toolName", ""));
            return toolCall(name, arguments, json.optString("hypothesis", ""));
        }
        List<String> evidence = new ArrayList<>();
        JSONArray ids = json.optJSONArray("evidenceIds");
        if (ids == null) {
            ids = json.optJSONArray("evidence");
        }
        if (ids != null) {
            for (int i = 0; i < ids.length(); i++) {
                evidence.add(ids.getString(i));
            }
        }
        String category = json.optString("category", "UNKNOWN");
        String rootCause = json.optString("rootCause", json.optString("reason", ""));
        String action = json.optString("suggestedAction", json.optString("suggestedFix", ""));
        if (type.equals("unknown") || type.equals("insufficient_evidence")) {
            return unknown(rootCause, evidence);
        }
        if (!type.equals("submit")) {
            // A mistyped decision must not silently become a conclusion candidate:
            // unknown types are unparseable output (bounded retry), not lenient submits.
            throw new JSONException("unknown decision type: " + type);
        }
        return submit(category, rootCause, evidence, json.optInt("confidence", 0), action);
    }

    private static Map<String, Object> copyArguments(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, value));
        return java.util.Collections.unmodifiableMap(copy);
    }
}
