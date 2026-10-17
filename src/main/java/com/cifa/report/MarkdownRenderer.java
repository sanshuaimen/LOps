package com.cifa.report;

import com.cifa.classification.FailureCategory;
import com.cifa.llm.AnalysisResult;

import java.util.List;

public final class MarkdownRenderer implements ReportRenderer {

    @Override
    public String render(FailureCategory category, AnalysisResult result, List<String> unverifiedEvidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔴 Build Failure Analysis\n\n");

        if (category == FailureCategory.INFRA_FLAKY) {
            sb.append("⚠️ Low confidence — likely infrastructure issue, not a code change\n\n");
        }

        sb.append("Root Cause\n").append(result.rootCause()).append("\n\n");

        sb.append("Evidence\n");
        for (String item : result.evidence()) {
            String suffix = unverifiedEvidence.contains(item) ? " (unverified)" : "";
            sb.append("- ").append(item).append(suffix).append('\n');
        }
        sb.append('\n');

        sb.append("Likely Fix\n").append(result.suggestedFix()).append("\n\n");

        sb.append("Confidence\n").append(result.confidence()).append("%\n");

        return sb.toString();
    }
}
