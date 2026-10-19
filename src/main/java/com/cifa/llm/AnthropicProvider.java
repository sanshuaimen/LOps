package com.cifa.llm;

import com.cifa.classification.FailureCategory;
import com.cifa.context.AnalysisContext;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

public final class AnthropicProvider implements LLMProvider {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 1024;
    /** Request-level bound: without it a silent server hangs client.send forever
     * (connectTimeout only bounds the TCP handshake, not the response). */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final String apiKey;
    private final String model;
    private final HttpClient client;

    public AnthropicProvider(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    // ponytail: hosted APIs are far more reliable at strict JSON than local models, so
    // this skips OllamaProvider's retry-with-stricter-prompt round trip — one call, one
    // fallback to a raw-text AnalysisResult if the schema doesn't validate.
    @Override
    public AnalysisResult analyze(AnalysisContext context) throws IOException, InterruptedException {
        HttpRequest request = buildRequest(apiKey, model, context);
        HttpResponse<String> response = send(request);
        if (isErrorStatus(response.statusCode())) {
            throw ioExceptionFor(response.statusCode(), "Anthropic");
        }
        try {
            String content = extractContent(response.body());
            return parseModelOutput(content);
        } catch (JSONException e) {
            String body = response.body();
            return new AnalysisResult(
                    "Anthropic API did not return valid JSON; showing raw response",
                    List.of(),
                    body.length() > 500 ? body.substring(0, 500) : body,
                    0,
                    context.category() == FailureCategory.INFRA_FLAKY
            );
        }
    }

    @Override
    public LLMResponse invoke(LLMRequest request) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildStructuredRequestBody(model, request)))
                .build();
        HttpResponse<String> response = send(httpRequest);
        if (isErrorStatus(response.statusCode())) {
            throw ioExceptionFor(response.statusCode(), "Anthropic");
        }
        ProviderReply reply = extractReply(response.body());
        return LLMResponse.parse(reply.text(), reply.inputTokens(), reply.outputTokens());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            // covers connect timeouts (subclass) and the request-level timeout above
            throw new TransientModelException("model request timed out after "
                    + REQUEST_TIMEOUT.getSeconds() + "s", e);
        }
    }

    /** Failure classification at the HTTP boundary: 429 is retryable-but-distinct. */
    static IOException ioExceptionFor(int statusCode, String provider) {
        if (statusCode == 429) {
            return new RateLimitedException(provider + " API returned HTTP 429 (rate limited)");
        }
        return new IOException(provider + " API returned HTTP " + statusCode);
    }

    public String model() {
        return model;
    }

    // P0 protocol decision (docs/decisions.md): no tools field on the wire. The tool
    // catalog travels as text in the system prompt; a native tool_use response would
    // otherwise crash extractContent (no text block) and the half-open state is worse
    // than no native tools at all.
    static String buildStructuredRequestBody(String model, LLMRequest request) {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());
        body.put("system", request.systemPrompt());
        JSONArray messages = new JSONArray();
        for (LLMMessage message : request.messages()) {
            if (!"system".equals(message.role())) {
                messages.put(new JSONObject().put("role", message.role()).put("content", message.content()));
            }
        }
        body.put("messages", messages);
        return body.toString();
    }

    static boolean isErrorStatus(int statusCode) {
        return statusCode >= 300;
    }

    static HttpRequest buildRequest(String apiKey, String model, AnalysisContext context) {
        return HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(model, context)))
                .build();
    }

    static String buildRequestBody(String model, AnalysisContext context) {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", OllamaProvider.SYSTEM_PROMPT);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", OllamaProvider.buildPrompt(context, false)));
        body.put("messages", messages);
        return body.toString();
    }

    static String extractContent(String responseBody) throws JSONException {
        return extractReply(responseBody).text();
    }

    static ProviderReply extractReply(String responseBody) throws JSONException {
        JSONObject json = new JSONObject(responseBody);
        JSONArray content = json.getJSONArray("content");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.getJSONObject(i);
            if ("text".equals(block.optString("type"))) {
                sb.append(block.getString("text"));
            }
        }
        if (sb.length() == 0) {
            throw new JSONException("no text content block in Anthropic response");
        }
        JSONObject usage = json.optJSONObject("usage");
        return new ProviderReply(sb.toString(),
                usage == null ? 0 : usage.optInt("input_tokens", 0),
                usage == null ? 0 : usage.optInt("output_tokens", 0));
    }

    static AnalysisResult parseModelOutput(String modelText) {
        return OllamaProvider.parseModelOutput(modelText);
    }
}
