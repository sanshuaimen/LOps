package com.cifa.llm;

import com.cifa.context.AnalysisContext;

import java.io.IOException;

@FunctionalInterface
public interface LLMProvider {
    AnalysisResult analyze(AnalysisContext context) throws IOException, InterruptedException;

    default LLMResponse invoke(LLMRequest request) throws IOException, InterruptedException {
        throw new UnsupportedOperationException("This provider does not support structured investigation calls");
    }
}
