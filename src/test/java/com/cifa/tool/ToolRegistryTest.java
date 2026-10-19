package com.cifa.tool;

import com.cifa.llm.ToolSchema;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    @Test
    void exposesSchemasForAllRegisteredTools() {
        ToolRegistry registry = new ToolRegistry()
                .register(new StubTool("tool_a"))
                .register(new StubTool("tool_b"));

        assertEquals(List.of("tool_a", "tool_b"), registry.names());
        List<ToolSchema> schemas = registry.schemas();
        assertEquals(2, schemas.size());
        assertEquals("tool_a", schemas.get(0).name());
    }

    @Test
    void unknownToolBecomesStructuredInvalidArgument() {
        ToolRegistry registry = new ToolRegistry().register(new StubTool("tool_a"));

        ToolResult result = registry.invoke("no_such_tool", Map.of());

        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertTrue(result.message().contains("unknown tool"));
    }

    @Test
    void wrapsCrashingToolAsFailedResult() {
        ToolRegistry registry = new ToolRegistry().register(new InvestigationTool() {
            @Override
            public String name() {
                return "exploding";
            }

            @Override
            public String description() {
                return "always crashes";
            }

            @Override
            public ToolSchema schema() {
                return ToolSchema.object("exploding", "always crashes");
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                throw new IllegalStateException("boom");
            }
        });

        ToolResult result = registry.invoke("exploding", null);

        assertEquals(ToolStatus.FAILED, result.status());
        assertTrue(result.message().contains("boom"));
    }

    @Test
    void rejectsDuplicateToolNames() {
        ToolRegistry registry = new ToolRegistry().register(new StubTool("tool_a"));

        assertThrows(IllegalArgumentException.class, () -> registry.register(new StubTool("tool_a")));
    }

    @Test
    void skipsTimeoutDeclaringToolWhenBudgetExhausted() {
        GatedTool tool = new GatedTool("gated", true);
        ToolRegistry registry = new ToolRegistry().register(tool);

        ToolResult result = registry.invoke("gated", Map.of(), Duration.ZERO);

        assertEquals(ToolStatus.TIMEOUT, result.status());
        assertTrue(result.message().contains("skipped"), result.message());
        assertEquals(0, tool.executions, "a budget-gated tool must not be started");
    }

    @Test
    void runsNonDeclaringToolRegardlessOfBudget() {
        GatedTool tool = new GatedTool("inmemory", false);
        ToolRegistry registry = new ToolRegistry().register(tool);

        ToolResult result = registry.invoke("inmemory", Map.of(), Duration.ofSeconds(-1));

        assertEquals(ToolStatus.OK, result.status());
        assertEquals(1, tool.executions);
    }

    @Test
    void runsTimeoutDeclaringToolWhenBudgetRemains() {
        GatedTool tool = new GatedTool("gated", true);
        ToolRegistry registry = new ToolRegistry().register(tool);

        ToolResult result = registry.invoke("gated", Map.of(), Duration.ofMinutes(1));

        assertEquals(ToolStatus.OK, result.status());
        assertEquals(1, tool.executions);
    }

    private record StubTool(String name) implements InvestigationTool {
        @Override
        public String description() {
            return "stub " + name;
        }

        @Override
        public ToolSchema schema() {
            return ToolSchema.object(name, description());
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            return ToolResult.ok("stub ran", List.of(), 0);
        }
    }

    /** Counts executions so tests can prove whether the tool was actually started. */
    private static final class GatedTool implements InvestigationTool {
        private final String name;
        private final boolean declaring;
        private int executions;

        private GatedTool(String name, boolean declaring) {
            this.name = name;
            this.declaring = declaring;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "gated stub " + name;
        }

        @Override
        public ToolSchema schema() {
            return ToolSchema.object(name, description());
        }

        @Override
        public boolean supportsTimeout() {
            return declaring;
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            executions++;
            return ToolResult.ok("ran", List.of(), 0);
        }
    }
}
