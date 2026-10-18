package com.cifa.llm;

/**
 * Raw model reply plus the usage numbers reported by the provider. Token
 * budgets are only real if they come from here — never estimated.
 */
public record ProviderReply(String text, int inputTokens, int outputTokens) {
    public ProviderReply {
        text = text == null ? "" : text;
    }
}
