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
import java.time.Duration;
import java.util.List;

public final class AnthropicProvider implements LLMProvider {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 1024;

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
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (isErrorStatus(response.statusCode())) {
            throw new IOException("Anthropic API returned HTTP " + response.statusCode());
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

    static boolean isErrorStatus(int statusCode) {
        return statusCode >= 300;
    }

    static HttpRequest buildRequest(String apiKey, String model, AnalysisContext context) {
        return HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
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
        return sb.toString();
    }

    static AnalysisResult parseModelOutput(String modelText) {
        return OllamaProvider.parseModelOutput(modelText);
    }
}
