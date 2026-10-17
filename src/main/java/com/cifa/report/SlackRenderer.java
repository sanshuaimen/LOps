package com.cifa.report;

import com.cifa.classification.FailureCategory;
import com.cifa.llm.AnalysisResult;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public final class SlackRenderer implements ReportRenderer {

    @Override
    public String render(FailureCategory category, AnalysisResult result, List<String> unverifiedEvidence) {
        JSONArray blocks = new JSONArray();

        blocks.put(section("*:red_circle: Build Failure Analysis*"));

        if (category == FailureCategory.INFRA_FLAKY) {
            blocks.put(section(":warning: *Low confidence* — likely infrastructure issue, not a code change"));
        }

        blocks.put(section("*Root Cause*\n" + escape(result.rootCause())));

        StringBuilder evidenceText = new StringBuilder("*Evidence*\n");
        for (String item : result.evidence()) {
            String suffix = unverifiedEvidence.contains(item) ? " _(unverified)_" : "";
            evidenceText.append("• ").append(escape(item)).append(suffix).append('\n');
        }
        blocks.put(section(evidenceText.toString().stripTrailing()));

        blocks.put(section("*Likely Fix*\n" + escape(result.suggestedFix())));
        blocks.put(section("*Confidence:* " + result.confidence() + "%"));

        JSONObject payload = new JSONObject();
        payload.put("blocks", blocks);
        return payload.toString(2);
    }

    private static JSONObject section(String mrkdwnText) {
        return new JSONObject()
                .put("type", "section")
                .put("text", new JSONObject().put("type", "mrkdwn").put("text", mrkdwnText));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
