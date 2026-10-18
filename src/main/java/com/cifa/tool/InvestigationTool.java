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
}
