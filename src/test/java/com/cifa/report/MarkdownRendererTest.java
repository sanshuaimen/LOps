package com.cifa.report;

import com.cifa.classification.FailureCategory;
import com.cifa.llm.AnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {

    @Test
    void rendersLogicErrorReport() {
        AnalysisResult result = new AnalysisResult(
                "NullPointerException in UserService.calculateDiscount()",
                List.of("UserServiceTest.testPremiumDiscount failed", "Stack trace points to line 9"),
                "Add null validation before accessing customer.getType().",
                84,
                false
        );

        String report = new MarkdownRenderer().render(FailureCategory.LOGIC_ERROR, result, List.of());

        assertTrue(report.startsWith("🔴 Build Failure Analysis\n\n"));
        assertFalse(report.contains("Low confidence"));
        assertTrue(report.contains("Root Cause\nNullPointerException in UserService.calculateDiscount()\n\n"));
        assertTrue(report.contains("- UserServiceTest.testPremiumDiscount failed\n"));
        assertTrue(report.contains("Likely Fix\nAdd null validation before accessing customer.getType().\n\n"));
        assertTrue(report.contains("Confidence\n84%\n"));
    }

    @Test
    void rendersInfraFlakyWarningBannerAndFlagsUnverifiedEvidence() {
        AnalysisResult result = new AnalysisResult(
                "Likely transient network issue",
                List.of("Connection timed out", "Fabricated commit reference"),
                "Re-run the job; check network/infra status.",
                20,
                true
        );

        String report = new MarkdownRenderer().render(
                FailureCategory.INFRA_FLAKY, result, List.of("Fabricated commit reference"));

        assertTrue(report.contains("Low confidence"));
        assertTrue(report.contains("- Fabricated commit reference (unverified)\n"));
        assertFalse(report.contains("- Connection timed out (unverified)"));
    }
}
