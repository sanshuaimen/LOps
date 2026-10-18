package com.cifa.llm;

import com.cifa.classification.FailureCategory;
import com.cifa.context.AnalysisContext;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicProviderTest {

    private static final String CLEAN_ANTHROPIC_RESPONSE = """
            {"content": [{"type": "text", "text": "{\\"rootCause\\": \\"NPE in PricingService\\", \\"evidence\\": [\\"frame 1\\"], \\"suggestedFix\\": \\"add a null check\\", \\"confidence\\": 90, \\"lowConfidenceInfra\\": false}"}]}""";

    @Test
    void extractContentJoinsTextBlocks() throws Exception {
        String content = AnthropicProvider.extractContent(CLEAN_ANTHROPIC_RESPONSE);
        assertTrue(content.contains("NPE in PricingService"));
    }

    @Test
    void extractContentThrowsWhenNoTextBlockPresent() {
        assertThrows(org.json.JSONException.class,
                () -> AnthropicProvider.extractContent("{\"content\": [{\"type\": \"tool_use\"}]}"));
    }

    @Test
    void extractReplyCarriesProviderReportedUsage() {
        String body = """
                {"content": [{"type": "text", "text": "{\\"type\\":\\"unknown\\"}"}],
                 "usage": {"input_tokens": 812, "output_tokens": 96}}
                """;
        ProviderReply reply = AnthropicProvider.extractReply(body);
        assertEquals(812, reply.inputTokens());
        assertEquals(96, reply.outputTokens());
        assertTrue(reply.text().contains("unknown"));
    }

    @Test
    void extractReplyTreatsMissingUsageAsZero() {
        ProviderReply reply = AnthropicProvider.extractReply(
                "{\"content\": [{\"type\": \"text\", \"text\": \"x\"}]}");
        assertEquals(0, reply.inputTokens());
        assertEquals(0, reply.outputTokens());
    }

    @Test
    void parseModelOutputParsesTheEmbeddedJson() throws Exception {
        String content = AnthropicProvider.extractContent(CLEAN_ANTHROPIC_RESPONSE);
        AnalysisResult result = AnthropicProvider.parseModelOutput(content);
        assertEquals("NPE in PricingService", result.rootCause());
        assertEquals(90, result.confidence());
    }

    @Test
    void buildRequestBodyIncludesModelSystemAndUserMessage() {
        AnalysisContextFixture ctx = new AnalysisContextFixture();
        String body = AnthropicProvider.buildRequestBody("claude-sonnet-5", ctx.context());
        JSONObject json = new JSONObject(body);

        assertEquals("claude-sonnet-5", json.getString("model"));
        assertTrue(json.getString("system").contains("build-failure analyst"));
        assertEquals(1, json.getJSONArray("messages").length());
        assertEquals("user", json.getJSONArray("messages").getJSONObject(0).getString("role"));
    }

    @Test
    void buildRequestSetsAuthHeaderAndUri() {
        AnalysisContextFixture ctx = new AnalysisContextFixture();
        HttpRequest request = AnthropicProvider.buildRequest("test-api-key", "claude-sonnet-5", ctx.context());

        assertEquals("https://api.anthropic.com/v1/messages", request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals("test-api-key", request.headers().firstValue("x-api-key").orElse(null));
        assertEquals("2023-06-01", request.headers().firstValue("anthropic-version").orElse(null));
    }

    @Test
    void isErrorStatusTreatsAnythingAtOrAbove300AsAnError() {
        assertFalse(AnthropicProvider.isErrorStatus(200));
        assertFalse(AnthropicProvider.isErrorStatus(299));
        assertTrue(AnthropicProvider.isErrorStatus(300));
        assertTrue(AnthropicProvider.isErrorStatus(401));
        assertTrue(AnthropicProvider.isErrorStatus(429));
        assertTrue(AnthropicProvider.isErrorStatus(500));
    }

    private static final class AnalysisContextFixture {
        AnalysisContext context() {
            return new AnalysisContext(
                    "java.lang.NullPointerException", "msg", java.util.List.of(), java.util.List.of(),
                    "", "", FailureCategory.LOGIC_ERROR, java.util.List.of());
        }
    }
}
