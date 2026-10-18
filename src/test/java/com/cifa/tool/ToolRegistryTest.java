package com.cifa.tool;

import com.cifa.llm.ToolSchema;
import org.junit.jupiter.api.Test;

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
}
