package com.cifa.tool;

import com.cifa.llm.ToolSchema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Name-addressable registry of the investigation tools a task may call.
 * The agent loop only ever talks to tools through this registry, so unknown
 * tool names become structured rejections instead of exceptions.
 */
public final class ToolRegistry {

    private final Map<String, InvestigationTool> tools = new LinkedHashMap<>();

    public ToolRegistry register(InvestigationTool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("tool and tool name are required");
        }
        InvestigationTool existing = tools.putIfAbsent(tool.name(), tool);
        if (existing != null) {
            throw new IllegalArgumentException("duplicate tool name: " + tool.name());
        }
        return this;
    }

    public List<ToolSchema> schemas() {
        List<ToolSchema> schemas = new ArrayList<>();
        for (InvestigationTool tool : tools.values()) {
            schemas.add(tool.schema());
        }
        return List.copyOf(schemas);
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public ToolResult invoke(String name, Map<String, Object> arguments) {
        return invoke(name, arguments, null);
    }

    /**
     * Budget-aware invocation. Tools that declare {@link InvestigationTool#supportsTimeout()}
     * are skipped with a structured TIMEOUT when the caller's remaining wall-clock
     * budget is already exhausted — starting a subprocess (or any unbounded wait)
     * with no budget left only guarantees a stop-condition exit one round later.
     * Non-declaring tools ignore the budget: pure in-memory work needs no gating.
     */
    public ToolResult invoke(String name, Map<String, Object> arguments, Duration remainingBudget) {
        InvestigationTool tool = name == null ? null : tools.get(name);
        if (tool == null) {
            return ToolResult.invalidArgument("unknown tool: " + name);
        }
        if (remainingBudget != null && remainingBudget.compareTo(Duration.ZERO) <= 0 && tool.supportsTimeout()) {
            return ToolResult.timeout("tool " + name + " skipped: wall-clock budget exhausted");
        }
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        try {
            return tool.execute(safeArguments);
        } catch (IllegalArgumentException e) {
            return ToolResult.invalidArgument(e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.failed("tool " + name + " crashed: " + e.getMessage());
        }
    }
}
