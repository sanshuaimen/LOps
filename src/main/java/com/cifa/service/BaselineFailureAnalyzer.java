package com.cifa.service;

import com.cifa.llm.AnalysisResult;
import com.cifa.llm.EvidenceValidator;
import com.cifa.llm.LLMProvider;

import java.io.IOException;
import java.util.List;

/** The original fixed-context, single-model-call pipeline kept as a comparison baseline. */
public final class BaselineFailureAnalyzer {
    private final DiagnosisTaskService taskService;

    public BaselineFailureAnalyzer() {
        this(new DiagnosisTaskService());
    }

    public BaselineFailureAnalyzer(DiagnosisTaskService taskService) {
        this.taskService = taskService;
    }

    public BaselineResult analyze(DiagnosisRequest request, LLMProvider provider)
            throws IOException, InterruptedException {
        return analyze(taskService.prepare(request), provider);
    }

    public BaselineResult analyze(PreparedDiagnosis preparation, LLMProvider provider)
            throws IOException, InterruptedException {
        AnalysisResult analysis = provider.analyze(preparation.context());
        List<String> unverified = EvidenceValidator.findUnverified(analysis, preparation.context());
        return new BaselineResult(preparation, analysis, unverified);
    }
}
