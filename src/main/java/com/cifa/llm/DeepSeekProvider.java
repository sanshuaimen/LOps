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

/**
 * DeepSeek hosted API (OpenAI-compatible /chat/completions wire format).
 * DeepSeek-V4.1-Flash-1017 is served under the stable model name "deepseek-flash".
 * Same reliability posture as AnthropicProvider: one call for the baseline path,
 * request-level timeout, 429 classified as retryable-but-distinct.
 */
public final class DeepSeekProvider implements LLMProvider {

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-flash";
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final HttpClient client;

    public DeepSeekProvider(String apiKey, String model) {
        this(apiKey, model, DEFAULT_BASE_URL, DEFAULT_REQUEST_TIMEOUT);
    }

    /** Injectable base URL / timeout for real-HTTP tests. */
    public DeepSeekProvider(String apiKey, String model, String baseUrl, Duration requestTimeout) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public AnalysisResult analyze(AnalysisContext context) throws IOException, InterruptedException {
        HttpResponse<String> response = send(buildRequest(context));
        if (isErrorStatus(response.statusCode())) {
            throw AnthropicProvider.ioExceptionFor(response.statusCode(), "DeepSeek");
        }
        try {
            return OllamaProvider.parseModelOutput(extractReply(response.body()).text());
        } catch (JSONException e) {
            String body = response.body();
            return new AnalysisResult(
                    "DeepSeek API did not return valid JSON; showing raw response",
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
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildStructuredRequestBody(model, request)))
                .build();
        HttpResponse<String> response = send(httpRequest);
        if (isErrorStatus(response.statusCode())) {
            throw AnthropicProvider.ioExceptionFor(response.statusCode(), "DeepSeek");
        }
        ProviderReply reply = extractReply(response.body());
        return LLMResponse.parse(reply.text(), reply.inputTokens(), reply.outputTokens());
    }

    private HttpRequest buildRequest(AnalysisContext context) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(model, context)))
                .build();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            // covers connect timeouts (subclass) and the request-level timeout above
            throw new TransientModelException("model request timed out after "
                    + requestTimeout.getSeconds() + "s", e);
        }
    }

    public String model() {
        return model;
    }

    static boolean isErrorStatus(int statusCode) {
        return statusCode >= 300;
    }

    /** OpenAI wire format: the system prompt travels as a system-role message.
     * No response_format on the wire (same decision as the Anthropic path — the
     * structured contract is enforced by parsing, not by provider flags). */
    static String buildStructuredRequestBody(String model, LLMRequest request) {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", request.systemPrompt()));
        for (LLMMessage message : request.messages()) {
            messages.put(new JSONObject().put("role", message.role()).put("content", message.content()));
        }
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", request.temperature())
                .put("max_tokens", request.maxTokens());
        return body.toString();
    }

    static String buildRequestBody(String model, AnalysisContext context) {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", OllamaProvider.SYSTEM_PROMPT))
                .put(new JSONObject().put("role", "user").put("content", OllamaProvider.buildPrompt(context, false)));
        return new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .toString();
    }

    static ProviderReply extractReply(String responseBody) throws JSONException {
        JSONObject json = new JSONObject(responseBody);
        JSONArray choices = json.getJSONArray("choices");
        if (choices.length() == 0) {
            throw new JSONException("no choices in DeepSeek response");
        }
        String content = choices.getJSONObject(0).optJSONObject("message") == null
                ? null
                : choices.getJSONObject(0).getJSONObject("message").optString("content", "");
        if (content == null || content.isEmpty()) {
            throw new JSONException("empty message content in DeepSeek response");
        }
        JSONObject usage = json.optJSONObject("usage");
        return new ProviderReply(content,
                usage == null ? 0 : usage.optInt("prompt_tokens", 0),
                usage == null ? 0 : usage.optInt("completion_tokens", 0));
    }
}
