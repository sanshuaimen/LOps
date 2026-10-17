package com.cifa.report;

import com.cifa.classification.FailureCategory;
import com.cifa.llm.AnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HtmlRendererTest {

    @Test
    void rendersLogicErrorReportAsWellFormedHtml() {
        AnalysisResult result = new AnalysisResult(
                "NullPointerException in UserService.calculateDiscount()",
                List.of("UserServiceTest.testPremiumDiscount failed", "Stack trace points to line 9"),
                "Add null validation before accessing customer.getType().",
                84,
                false
        );

        String html = new HtmlRenderer().render(FailureCategory.LOGIC_ERROR, result, List.of());

        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("</html>"));
        assertFalse(html.contains("Low confidence"));
        assertTrue(html.contains("NullPointerException in UserService.calculateDiscount()"));
        assertTrue(html.contains("<li>UserServiceTest.testPremiumDiscount failed</li>"));
        assertTrue(html.contains("84%"));
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

        String html = new HtmlRenderer().render(
                FailureCategory.INFRA_FLAKY, result, List.of("Fabricated commit reference"));

        assertTrue(html.contains("Low confidence"));
        assertTrue(html.contains("Fabricated commit reference"));
        assertTrue(html.contains("(unverified)"));
        assertTrue(html.contains("Fabricated commit reference <em>(unverified)</em></li>"));
        assertFalse(html.contains("Connection timed out <em>(unverified)</em></li>"));
    }

    @Test
    void escapesHtmlSpecialCharactersInDynamicContent() {
        AnalysisResult result = new AnalysisResult(
                "<script>alert(1)</script>",
                List.of(),
                "fix",
                50,
                false
        );

        String html = new HtmlRenderer().render(FailureCategory.LOGIC_ERROR, result, List.of());

        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }
}
