package com.cifa.context;

public record ContextBudget(int maxFrames, int maxSnippetChars, int maxDiffChars) {
    public static final ContextBudget DEFAULT = new ContextBudget(8, 1500, 2000);
}
