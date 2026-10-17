package com.cifa.llm;

import java.util.List;

public record AnalysisResult(
        String rootCause,
        List<String> evidence,
        String suggestedFix,
        int confidence,
        boolean lowConfidenceInfra
) {
}
