package com.cifa.llm;

import com.cifa.classification.FailureCategory;
import com.cifa.context.AnalysisContext;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

public final class FakeLLMProvider implements LLMProvider {
    private final Queue<Object> scripted = new ArrayDeque<>();
    private final List<LLMRequest> requests = new ArrayList<>();
    private final List<LLMResponse> responses = new ArrayList<>();

    public FakeLLMProvider() {
    }

    public FakeLLMProvider(List<AgentDecision> decisions) {
        decisions.forEach(this::enqueue);
    }

    public FakeLLMProvider(AgentDecision... decisions) {
        for (AgentDecision decision : decisions) {
            enqueue(decision);
        }
    }

    /**
     * Builds a provider from a recorded script: a JSON file holding an array
     * of decision objects, as captured from a real run or written by hand for
     * offline evals. Lets the full agent path run with zero network access.
     */
    public static FakeLLMProvider recorded(java.nio.file.Path scriptFile) throws java.io.IOException {
        org.json.JSONArray array = new org.json.JSONArray(java.nio.file.Files.readString(scriptFile));
        FakeLLMProvider provider = new FakeLLMProvider();
        for (int i = 0; i < array.length(); i++) {
            provider.enqueue(AgentDecision.fromJson(array.getJSONObject(i).toString()));
        }
        return provider;
    }

    public FakeLLMProvider enqueue(AgentDecision decision) {
        scripted.add(decision);
        return this;
    }

    public FakeLLMProvider enqueueRaw(String response) {
        scripted.add(response);
        return this;
    }

    public FakeLLMProvider enqueueFailure(Exception failure) {
        scripted.add(failure);
        return this;
    }

    public List<LLMRequest> requests() {
        return Collections.unmodifiableList(requests);
    }

    public List<LLMResponse> responses() {
        return Collections.unmodifiableList(responses);
    }

    @Override
    public LLMResponse invoke(LLMRequest request) throws IOException, InterruptedException {
        requests.add(request);
        long started = System.nanoTime();
        Object next = scripted.poll();
        if (next instanceof IOException ioException) {
            throw ioException;
        }
        if (next instanceof InterruptedException interruptedException) {
            throw interruptedException;
        }
        if (next instanceof Exception exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        LLMResponse response;
        if (next == null) {
            response = LLMResponse.of(AgentDecision.unknown("No scripted response available"));
        } else if (next instanceof AgentDecision decision) {
            response = LLMResponse.of(decision);
        } else {
            response = LLMResponse.parse((String) next);
        }
        int inputTokens = Math.max(1, request.userPrompt().length() / 4);
        int outputTokens = Math.max(1, response.content().length() / 4);
        response = new LLMResponse(response.content(), response.decision(), inputTokens, outputTokens,
                (System.nanoTime() - started) / 1_000_000);
        responses.add(response);
        return response;
    }

    @Override
    public AnalysisResult analyze(AnalysisContext context) throws IOException, InterruptedException {
        if (!scripted.isEmpty()) {
            LLMResponse response = invoke(new LLMRequest(
                    "Fake baseline", OllamaProvider.buildPrompt(context, false), List.of()));
            AgentDecision decision = response.decision();
            return new AnalysisResult(decision.rootCause(), decision.evidenceIds(),
                    decision.suggestedAction(), decision.confidence(),
                    context.category() == FailureCategory.INFRA_FLAKY);
        }
        return heuristicAnalysis(context);
    }

    private static AnalysisResult heuristicAnalysis(AnalysisContext context) {
        if (context.category() == FailureCategory.UNKNOWN) {
            return new AnalysisResult("UNKNOWN", List.of(), "", 0, false);
        }
        if (context.category() == FailureCategory.INFRA_FLAKY) {
            return new AnalysisResult("Infrastructure timeout or connectivity failure",
                    List.of(context.errorType()), "Retry after checking the external service", 20, true);
        }
        if (context.category() == FailureCategory.DEPENDENCY_BUILD) {
            return new AnalysisResult("Dependency or compilation failure: " + context.message(),
                    List.of(context.message()), "Check the dependency and symbol versions", 50, false);
        }
        String evidence = context.topFrames().isEmpty() ? context.message()
                : context.topFrames().get(0).fileName() + ":" + context.topFrames().get(0).lineNumber();
        return new AnalysisResult("Null or logic failure in the reported code path: " + context.message(),
                List.of(evidence), "Inspect the failing code path", 70, false);
    }
}
