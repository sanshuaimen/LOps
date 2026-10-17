package com.cifa.report;

import com.cifa.classification.FailureCategory;
import com.cifa.llm.AnalysisResult;

import java.util.List;

public interface ReportRenderer {
    String render(FailureCategory category, AnalysisResult result, List<String> unverifiedEvidence);
}
