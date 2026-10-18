package com.cifa.llm;

public record LLMResponse(
        String content,
        AgentDecision decision,
        int inputTokens,
        int outputTokens,
        long latencyMillis
) {
    public LLMResponse {
        content = content == null ? "" : content;
    }

    public static LLMResponse of(AgentDecision decision) {
        return new LLMResponse(decision.toJson().toString(), decision, 0, 0, 0);
    }

    public static LLMResponse of(AgentDecision decision, int inputTokens, int outputTokens, long latencyMillis) {
        return new LLMResponse(decision.toJson().toString(), decision, inputTokens, outputTokens, latencyMillis);
    }

    public static LLMResponse parse(String content) {
        AgentDecision decision = AgentDecision.fromJson(content);
        return new LLMResponse(content, decision, 0, 0, 0);
    }

    /** Parse with real provider-reported usage so token budgets bind in production. */
    public static LLMResponse parse(String content, int inputTokens, int outputTokens) {
        AgentDecision decision = AgentDecision.fromJson(content);
        return new LLMResponse(content, decision, inputTokens, outputTokens, 0);
    }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
