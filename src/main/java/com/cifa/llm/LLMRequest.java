package com.cifa.llm;

import java.util.List;

public record LLMRequest(
        String systemPrompt,
        List<LLMMessage> messages,
        List<ToolSchema> tools,
        int maxTokens,
        double temperature
) {
    public LLMRequest {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("at least one message is required");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (!Double.isFinite(temperature) || temperature < 0) {
            throw new IllegalArgumentException("temperature must be finite and non-negative");
        }
    }

    public LLMRequest(String systemPrompt, List<LLMMessage> messages, List<ToolSchema> tools) {
        this(systemPrompt, messages, tools, 1024, 0.0);
    }

    public LLMRequest(String systemPrompt, String userPrompt, List<ToolSchema> tools) {
        this(systemPrompt, List.of(LLMMessage.user(userPrompt)), tools);
    }

    public String userPrompt() {
        StringBuilder prompt = new StringBuilder();
        for (LLMMessage message : messages) {
            if (prompt.length() > 0) {
                prompt.append('\n');
            }
            prompt.append(message.content());
        }
        return prompt.toString();
    }
}
