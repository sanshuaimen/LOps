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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class OllamaProvider implements LLMProvider {

    // ponytail: local models are less reliable at strict JSON — one retry with a
    // stricter instruction, then a raw-text fallback. Upgrade path if this proves
    // insufficient: a JSON-repair pass before giving up.
    private static final Pattern THINK_BLOCK = Pattern.compile("(?s)<think>.*?</think>");

    static final String SYSTEM_PROMPT =
            "You are a build-failure analyst. Only reason from the evidence given. "
            + "Never invent file names, line numbers, or commits not present in the input. "
            + "Respond with JSON only, matching exactly: "
            + "{\"rootCause\": string, \"evidence\": [string], \"suggestedFix\": string, "
            + "\"confidence\": number 0-100, \"lowConfidenceInfra\": boolean}. "
            + "If the failure category is INFRA_FLAKY, set lowConfidenceInfra to true, keep confidence low, "
            + "and say code correlation is unreliable rather than inventing a code-level root cause.";

    private final String baseUrl;
    private final String model;
    private final HttpClient client;

    public OllamaProvider(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public AnalysisResult analyze(AnalysisContext context) throws IOException, InterruptedException {
        String firstRaw = null;
        try {
            firstRaw = callOllama(buildPrompt(context, false));
            return parseModelOutput(firstRaw);
        } catch (JSONException firstFailure) {
            String retryRaw = null;
            try {
                retryRaw = callOllama(buildPrompt(context, true));
                return parseModelOutput(retryRaw);
            } catch (JSONException secondFailure) {
                String rawForDisplay = retryRaw != null ? retryRaw
                        : (firstRaw != null ? firstRaw : "(no readable response from Ollama)");
                return new AnalysisResult(
                        "LLM did not return valid JSON after retry; showing raw response",
                        List.of(),
                        rawForDisplay.length() > 500 ? rawForDisplay.substring(0, 500) : rawForDisplay,
                        0,
                        context.category() == FailureCategory.INFRA_FLAKY
                );
            }
        }
    }

    @Override
    public LLMResponse invoke(LLMRequest request) throws IOException, InterruptedException {
        ProviderReply reply = extractReply(callOllama(request));
        return LLMResponse.parse(THINK_BLOCK.matcher(reply.text()).replaceAll("").trim(),
                reply.inputTokens(), reply.outputTokens());
    }

    public String model() {
        return model;
    }

    String callOllama(LLMRequest request) throws IOException, InterruptedException, JSONException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildStructuredRequestBody(model, request)))
                .build();
        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        return extractContent(response.body());
    }

    static String buildStructuredRequestBody(String model, LLMRequest request) {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("stream", false);
        // Ollama ignores a top-level max_tokens; generation limits live in options.num_predict.
        body.put("options", new JSONObject()
                .put("num_predict", request.maxTokens())
                .put("temperature", request.temperature()));
        JSONArray messages = new JSONArray();
        if (!request.systemPrompt().isBlank()) {
            messages.put(new JSONObject().put("role", "system").put("content", request.systemPrompt()));
        }
        for (LLMMessage message : request.messages()) {
            messages.put(new JSONObject().put("role", message.role()).put("content", message.content()));
        }
        body.put("messages", messages);
        // P0 protocol decision (docs/decisions.md): no tools field on the wire —
        // models that answer with message.tool_calls leave message.content
        // missing and extractContent would throw. Catalog text lives in the
        // system prompt instead.
        return body.toString();
    }

    String callOllama(String prompt) throws IOException, InterruptedException, JSONException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("stream", false);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
        messages.put(new JSONObject().put("role", "user").put("content", prompt));
        body.put("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return extractContent(response.body());
    }

    static String extractContent(String responseBody) throws JSONException {
        return extractReply(responseBody).text();
    }

    static ProviderReply extractReply(String responseBody) throws JSONException {
        JSONObject json = new JSONObject(responseBody);
        String content = json.getJSONObject("message").getString("content");
        return new ProviderReply(content,
                json.optInt("prompt_eval_count", 0),
                json.optInt("eval_count", 0));
    }

    static String buildPrompt(AnalysisContext ctx, boolean strict) {
        StringBuilder sb = new StringBuilder();
        if (strict) {
            sb.append("Your previous response was not valid JSON. Respond with ONLY the JSON object, no prose, no markdown fences.\n\n");
        }
        sb.append("Failure category: ").append(ctx.category()).append('\n');
        sb.append("Error type: ").append(ctx.errorType()).append('\n');
        sb.append("Message: ").append(ctx.message()).append('\n');
        sb.append("Failing tests: ").append(String.join(", ", ctx.failingTests())).append('\n');
        sb.append("Stack frames:\n");
        for (var frame : ctx.topFrames()) {
            sb.append("  ").append(frame.className()).append('.').append(frame.methodName())
                    .append('(').append(frame.fileName()).append(':').append(frame.lineNumber()).append(")\n");
        }
        sb.append("Source snippet:\n").append(ctx.sourceSnippet()).append('\n');
        sb.append("Relevant diff hunk:\n").append(ctx.diffHunk().isEmpty() ? "(none)" : ctx.diffHunk()).append('\n');
        if (!ctx.excludedNotes().isEmpty()) {
            sb.append("Context notes: ").append(String.join("; ", ctx.excludedNotes())).append('\n');
        }
        return sb.toString();
    }

    static AnalysisResult parseModelOutput(String modelText) {
        String stripped = THINK_BLOCK.matcher(modelText).replaceAll("").trim();
        int firstBrace = stripped.indexOf('{');
        int lastBrace = stripped.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace < firstBrace) {
            throw new JSONException("no JSON object found in model output");
        }
        JSONObject json = new JSONObject(stripped.substring(firstBrace, lastBrace + 1));
        List<String> evidence = new ArrayList<>();
        JSONArray evidenceArray = json.getJSONArray("evidence");
        for (int i = 0; i < evidenceArray.length(); i++) {
            evidence.add(evidenceArray.getString(i));
        }
        return new AnalysisResult(
                json.getString("rootCause"),
                evidence,
                json.getString("suggestedFix"),
                json.getInt("confidence"),
                json.optBoolean("lowConfidenceInfra", false)
        );
    }
}
