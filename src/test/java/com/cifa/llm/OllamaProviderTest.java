package com.cifa.llm;

import org.json.JSONException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OllamaProviderTest {

    private static final String CLEAN_JSON = """
            {"rootCause": "NPE in UserService", "evidence": ["frame 1", "frame 2"], \
            "suggestedFix": "add a null check", "confidence": 84, "lowConfidenceInfra": false}""";

    @Test
    void parsesCleanJson() {
        AnalysisResult result = OllamaProvider.parseModelOutput(CLEAN_JSON);
        assertEquals("NPE in UserService", result.rootCause());
        assertEquals(2, result.evidence().size());
        assertEquals(84, result.confidence());
        assertFalse(result.lowConfidenceInfra());
    }

    @Test
    void stripsThinkBlockBeforeParsing() {
        String wrapped = "<think>reasoning about the bug here</think>\n" + CLEAN_JSON;
        AnalysisResult result = OllamaProvider.parseModelOutput(wrapped);
        assertEquals("NPE in UserService", result.rootCause());
    }

    @Test
    void throwsOnMalformedJson() {
        assertThrows(JSONException.class, () -> OllamaProvider.parseModelOutput("not json at all"));
    }

    @Test
    void extractContentThrowsOnMissingMessageField() {
        assertThrows(JSONException.class, () -> OllamaProvider.extractContent("{\"error\": \"model not found\"}"));
    }

    @Test
    void extractContentReturnsMessageContent() {
        String body = "{\"message\": {\"role\": \"assistant\", \"content\": \"hello\"}}";
        assertEquals("hello", OllamaProvider.extractContent(body));
    }
}
