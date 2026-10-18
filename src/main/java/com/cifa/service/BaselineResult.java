package com.cifa.service;

import com.cifa.llm.AnalysisResult;

import java.util.List;

public record BaselineResult(
        PreparedDiagnosis preparation,
        AnalysisResult analysis,
        List<String> unverifiedEvidence
) {
    public BaselineResult {
        unverifiedEvidence = List.copyOf(unverifiedEvidence);
    }
}
