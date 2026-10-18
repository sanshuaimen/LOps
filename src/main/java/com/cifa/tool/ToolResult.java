package com.cifa.tool;

import java.util.List;

/**
 * Structured outcome of one controlled tool invocation. Tools never throw at
 * the agent loop boundary; every failure mode maps to a status the loop and
 * the model can reason about.
 */
public record ToolResult(
        ToolStatus status,
        String message,
        List<String> evidenceIds,
        long elapsedMillis
) {
    public ToolResult {
        message = message == null ? "" : message;
        evidenceIds = List.copyOf(evidenceIds);
    }

    public static ToolResult ok(String message, List<String> evidenceIds, long elapsedMillis) {
        return new ToolResult(ToolStatus.OK, message, evidenceIds, elapsedMillis);
    }

    public static ToolResult invalidArgument(String message) {
        return new ToolResult(ToolStatus.INVALID_ARGUMENT, message, List.of(), 0);
    }

    public static ToolResult failed(String message) {
        return new ToolResult(ToolStatus.FAILED, message, List.of(), 0);
    }

    public static ToolResult timeout(String message) {
        return new ToolResult(ToolStatus.TIMEOUT, message, List.of(), 0);
    }

    public static ToolResult noData(String message) {
        return new ToolResult(ToolStatus.NO_DATA, message, List.of(), 0);
    }

    public boolean success() {
        return status == ToolStatus.OK;
    }
}
