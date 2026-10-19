package com.cifa.tool;

import com.cifa.llm.ToolSchema;

import java.util.Map;

/**
 * A controlled, read-only investigation tool. Implementations validate their
 * arguments against a whitelist, enforce output limits, and register every
 * result in the task's {@link com.cifa.evidence.EvidenceCatalog}.
 */
public interface InvestigationTool {

    String name();

    String description();

    ToolSchema schema();

    ToolResult execute(Map<String, Object> arguments);

    /**
     * Whether this tool can stall long enough to be worth budget-gating: the
     * registry skips declaring tools when the caller's remaining wall-clock
     * budget is exhausted, instead of starting work that cannot be bounded.
     * Pure in-memory tools keep the default (false) — there is nothing to gate.
     */
    default boolean supportsTimeout() {
        return false;
    }
}
