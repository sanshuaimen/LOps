package com.cifa.llm;

import com.cifa.classification.FailureCategory;
import com.cifa.context.AnalysisContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP classification tests over an in-JVM server (JDK httpserver — zero
 * new dependencies). DeepSeek's OpenAI-compatible wire format: choices[].message,
 * usage.prompt_tokens/completion_tokens, Bearer auth.
 */
class DeepSeekProviderHttpTest {

    private static final LLMRequest REQUEST = new LLMRequest("system prompt", "user prompt", List.of());

    private HttpServer server;
    private final AtomicReference<String> capturedAuthHeader = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private DeepSeekProvider startServer(Duration requestTimeout) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        return new DeepSeekProvider("test-key", "deepseek-flash",
                "http://localhost:" + server.getAddress().getPort(), requestTimeout);
    }

    private void respondWith(int statusCode, String body) {
        server.createContext("/chat/completions", exchange -> {
            capturedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    @Test
    void invokeParsesChoicesMessageAndUsage() throws Exception {
        DeepSeekProvider provider = startServer(Duration.ofSeconds(5));
        respondWith(200, """
                {"choices": [{"message": {"role": "assistant", "content": "{\\"type\\":\\"unknown\\",\\"reason\\":\\"no signal\\"}"}}],
                 "usage": {"prompt_tokens": 120, "completion_tokens": 30}}
                """);

        LLMResponse response = provider.invoke(REQUEST);

        assertEquals("Bearer test-key", capturedAuthHeader.get());
        assertTrue(capturedBody.get().contains("\"model\":\"deepseek-flash\""));
        assertTrue(capturedBody.get().contains("\"role\":\"system\""), "system prompt travels as a message");
        assertTrue(response.content().contains("\"type\":\"unknown\""), response.content());
        assertEquals(120, response.inputTokens());
        assertEquals(30, response.outputTokens());
    }

    @Test
    void invokeRejectsEmptyChoicesAsMalformed() throws Exception {
        DeepSeekProvider provider = startServer(Duration.ofSeconds(5));
        respondWith(200, "{\"choices\": [], \"usage\": {}}");

        // org.json's JSONException is unchecked: it flows to the agent loop's
        // unparseable-output handling, exactly like Ollama's malformed bodies.
        assertThrows(org.json.JSONException.class, () -> provider.invoke(REQUEST));
    }

    @Test
    void rateLimitsAreClassifiedAsRetryableButDistinct() throws Exception {
        DeepSeekProvider provider = startServer(Duration.ofSeconds(5));
        respondWith(429, "{\"error\": {\"message\": \"rate limited\"}}");

        IOException error = assertThrows(IOException.class, () -> provider.invoke(REQUEST));
        assertInstanceOf(RateLimitedException.class, error);
    }

    @Test
    void hangingServerTimesOutIntoTransientCategory() throws Exception {
        DeepSeekProvider provider = startServer(Duration.ofMillis(50));
        server.createContext("/chat/completions", exchange -> {
            try {
                Thread.sleep(2000); // hold the connection well past the request timeout
            } catch (InterruptedException ignored) {
                // fall through to close
            }
            exchange.close();
        });

        IOException error = assertThrows(IOException.class, () -> provider.invoke(REQUEST));
        assertInstanceOf(TransientModelException.class, error,
                "a hung response must surface as the transient category, not block forever");
    }

    @Test
    void analyzeFallsBackToRawTextWhenSchemaIsInvalid() throws Exception {
        DeepSeekProvider provider = startServer(Duration.ofSeconds(5));
        respondWith(200, """
                {"choices": [{"message": {"role": "assistant", "content": "plain prose, not the structured schema"}}],
                 "usage": {"prompt_tokens": 10, "completion_tokens": 5}}
                """);
        AnalysisContext context = new AnalysisContext(
                "java.lang.NullPointerException", "msg", List.of(), List.of(),
                "", "", FailureCategory.LOGIC_ERROR, List.of());

        AnalysisResult result = provider.analyze(context);

        assertTrue(result.rootCause().contains("plain prose") || result.rootCause().contains("raw response"),
                "non-schema output degrades to the raw-text fallback: " + result.rootCause());
    }
}
