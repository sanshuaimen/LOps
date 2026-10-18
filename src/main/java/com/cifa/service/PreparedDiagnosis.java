package com.cifa.service;

import com.cifa.classification.ClassifiedFailure;
import com.cifa.context.AnalysisContext;
import com.cifa.correlation.CorrelatedCommit;

import java.util.List;

public record PreparedDiagnosis(
        String normalizedLog,
        ClassifiedFailure classified,
        CorrelatedCommit commit,
        String sourceSnippet,
        List<String> correlationNotes,
        AnalysisContext context,
        boolean usedXmlReport,
        boolean correlationAttempted
) {
    public PreparedDiagnosis {
        correlationNotes = List.copyOf(correlationNotes);
    }
}
