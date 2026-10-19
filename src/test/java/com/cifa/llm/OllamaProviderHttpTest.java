package com.cifa.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP classification tests over an in-JVM server (JDK httpserver — zero
 * new dependencies). The base URL is injectable, so 429/500/hang fail the full
 * provider path — including the request-level timeout, which static builder
 * tests cannot exercise.
 */
class OllamaProviderHttpTest {

    private static final LLMRequest REQUEST = new LLMRequest("system prompt", "user prompt", List.of());

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OllamaProvider startServer(Duration requestTimeout) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        return new OllamaProvider("http://localhost:" + server.getAddress().getPort(), "test-model", requestTimeout);
    }

    private void respondWith(int statusCode, String body) {
        server.createContext("/api/chat", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    @Test
    void invokeParsesAHealthyChatResponse() throws Exception {
        OllamaProvider provider = startServer(Duration.ofSeconds(5));
        respondWith(200, "{\"message\": {\"content\": \"{\\\"type\\\":\\\"unknown\\\",\\\"reason\\\":\\\"no signal\\\"}\"}}");

        LLMResponse response = provider.invoke(REQUEST);

        assertEquals(AgentDecision.Kind.UNKNOWN, response.decision().kind());
    }

    @Test
    void rateLimitAnswersAsRateLimitedException() throws Exception {
        OllamaProvider provider = startServer(Duration.ofSeconds(5));
        respondWith(429, "{\"error\": \"too many requests\"}");

        IOException e = assertThrows(IOException.class, () -> provider.invoke(REQUEST));

        assertInstanceOf(RateLimitedException.class, e);
        assertTrue(e.getMessage().contains("429"), e.getMessage());
    }

    @Test
    void serverErrorsStayPlainIoExceptions() throws Exception {
        OllamaProvider provider = startServer(Duration.ofSeconds(5));
        respondWith(500, "internal error");

        IOException e = assertThrows(IOException.class, () -> provider.invoke(REQUEST));

        assertFalse(e instanceof RateLimitedException);
        assertTrue(e.getMessage().contains("500"), e.getMessage());
    }

    @Test
    void aHangingServerHitsTheRequestLevelTimeout() throws Exception {
        OllamaProvider provider = startServer(Duration.ofMillis(50));
        server.createContext("/api/chat", exchange -> {
            try {
                Thread.sleep(1000); // outlives the 50ms request timeout on purpose
            } catch (InterruptedException ignored) {
                // server stop interrupted the sleep; nothing to finish
            }
            try {
                exchange.sendResponseHeaders(200, 2);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write("{}".getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException closed) {
                // client already timed out and walked away
            }
        });

        TransientModelException e = assertThrows(TransientModelException.class,
                () -> provider.invoke(REQUEST));

        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
    }
}
