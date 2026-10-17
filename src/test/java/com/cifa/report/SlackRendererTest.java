package com.cifa.report;

import com.cifa.classification.FailureCategory;
import com.cifa.llm.AnalysisResult;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlackRendererTest {

    @Test
    void rendersLogicErrorAsWellFormedBlockKitJson() {
        AnalysisResult result = new AnalysisResult(
                "NullPointerException in UserService.calculateDiscount()",
                List.of("UserServiceTest.testPremiumDiscount failed"),
                "Add null validation before accessing customer.getType().",
                84,
                false
        );

        String payload = new SlackRenderer().render(FailureCategory.LOGIC_ERROR, result, List.of());
        JSONObject json = new JSONObject(payload);

        assertTrue(json.has("blocks"));
        String allText = allBlockText(json);
        assertFalse(allText.contains("Low confidence"));
        assertTrue(allText.contains("NullPointerException in UserService.calculateDiscount()"));
        assertTrue(allText.contains("84%"));
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

        String payload = new SlackRenderer().render(
                FailureCategory.INFRA_FLAKY, result, List.of("Fabricated commit reference"));
        JSONObject json = new JSONObject(payload);
        String allText = allBlockText(json);

        assertTrue(allText.contains("Low confidence"));
        assertTrue(allText.contains("Fabricated commit reference"));
        assertTrue(allText.contains("(unverified)"));
    }

    private static String allBlockText(JSONObject payload) {
        StringBuilder sb = new StringBuilder();
        var blocks = payload.getJSONArray("blocks");
        for (int i = 0; i < blocks.length(); i++) {
            sb.append(blocks.getJSONObject(i).getJSONObject("text").getString("text")).append('\n');
        }
        return sb.toString();
    }
}
