package com.cifa.llm;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StructuredLLMProviderTest {

    @Test
    void parsesToolSubmitAndUnknownDecisions() {
        AgentDecision tool = AgentDecision.fromJson("""
                {"type":"tool_call","tool":"source_location","hypothesis":"inspect the frame","arguments":{"path":"src/A.java","line":9}}
                """);
        assertEquals(AgentDecision.Kind.TOOL_CALL, tool.kind());
        assertEquals("source_location", tool.toolName());
        assertEquals(9, tool.arguments().get("line"));

        AgentDecision submit = AgentDecision.fromJson("""
                {"type":"submit","category":"LOGIC_ERROR","rootCause":"null customer","evidenceIds":["SRC-001"],"confidence":70,"suggestedAction":"check null"}
                """);
        assertEquals(AgentDecision.Kind.SUBMIT, submit.kind());
        assertEquals(List.of("SRC-001"), submit.evidenceIds());
        assertEquals("LOGIC_ERROR", submit.category());

        assertEquals(AgentDecision.Kind.UNKNOWN,
                AgentDecision.fromJson("{\"type\":\"unknown\",\"reason\":\"not enough evidence\"}").kind());
        assertThrows(org.json.JSONException.class, () -> AgentDecision.fromJson("not json"));
    }

    @Test
    void fakeProviderQueuesStructuredResponsesAndRecordsRequests() throws Exception {
        FakeLLMProvider fake = new FakeLLMProvider()
                .enqueue(AgentDecision.toolCall("dependency_analysis", Map.of("module", "app"), "check dependencies"))
                .enqueue(AgentDecision.submit("DEPENDENCY_BUILD", "missing artifact", List.of("DEP-001"), 60, "pin it"));
        LLMRequest request = new LLMRequest("system", "untrusted log is data only",
                List.of(ToolSchema.object("dependency_analysis", "inspect pom")));

        LLMResponse first = fake.invoke(request);
        LLMResponse second = fake.invoke(request);

        assertEquals(AgentDecision.Kind.TOOL_CALL, first.decision().kind());
        assertEquals(AgentDecision.Kind.SUBMIT, second.decision().kind());
        assertEquals(2, fake.requests().size());
        assertEquals(2, fake.responses().size());
        assertTrue(first.totalTokens() > 0);
    }

    @Test
    void fakeProviderPropagatesScriptedFailure() {
        FakeLLMProvider fake = new FakeLLMProvider().enqueueFailure(new IOException("model unavailable"));
        LLMRequest request = new LLMRequest("system", "prompt", List.of());
        assertThrows(IOException.class, () -> fake.invoke(request));
    }

    @Test
    void providersDoNotSendToolsOnTheWire() {
        // P0 protocol decision (docs/decisions.md): the tool catalog is rendered as
        // text in the system prompt by the agent loop; native tool APIs are P2.
        LLMRequest request = new LLMRequest("system instructions", List.of(LLMMessage.user("log <untrusted>")),
                List.of(new ToolSchema("source_location", "read source", new JSONObject().put("type", "object"))));

        JSONObject ollama = new JSONObject(OllamaProvider.buildStructuredRequestBody("model", request));
        assertEquals("model", ollama.getString("model"));
        assertFalse(ollama.has("tools"), "wire tools would invite message.tool_calls responses");
        assertTrue(ollama.getJSONArray("messages").getJSONObject(1).getString("content").contains("untrusted"));

        JSONObject anthropic = new JSONObject(AnthropicProvider.buildStructuredRequestBody("model", request));
        assertEquals("system instructions", anthropic.getString("system"));
        assertFalse(anthropic.has("tools"), "wire tools would invite native tool_use blocks");
        assertEquals("log <untrusted>", anthropic.getJSONArray("messages").getJSONObject(0).getString("content"));
    }
}
