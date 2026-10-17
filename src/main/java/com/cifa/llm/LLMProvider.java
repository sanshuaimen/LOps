package com.cifa.llm;

import com.cifa.context.AnalysisContext;

import java.io.IOException;

public interface LLMProvider {
    AnalysisResult analyze(AnalysisContext context) throws IOException, InterruptedException;
}
