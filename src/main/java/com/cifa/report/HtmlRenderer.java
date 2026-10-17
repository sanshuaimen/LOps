package com.cifa.report;

import com.cifa.classification.FailureCategory;
import com.cifa.llm.AnalysisResult;

import java.util.List;

public final class HtmlRenderer implements ReportRenderer {

    @Override
    public String render(FailureCategory category, AnalysisResult result, List<String> unverifiedEvidence) {
        StringBuilder evidenceItems = new StringBuilder();
        for (String item : result.evidence()) {
            String suffix = unverifiedEvidence.contains(item) ? " <em>(unverified)</em>" : "";
            evidenceItems.append("<li>").append(escape(item)).append(suffix).append("</li>").append('\n');
        }

        String banner = category == FailureCategory.INFRA_FLAKY
                ? "<p class=\"banner\">⚠️ Low confidence — likely infrastructure issue, not a code change</p>\n"
                : "";

        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <title>CI Failure Analysis</title>
                <style>
                  body { font-family: -apple-system, "Segoe UI", sans-serif; max-width: 720px; margin: 2rem auto; line-height: 1.5; color: #1a1a1a; padding: 0 1rem; }
                  h1 { font-size: 1.4rem; }
                  h2 { font-size: 1.1rem; margin-top: 1.5rem; border-bottom: 1px solid #ddd; padding-bottom: .25rem; }
                  .banner { background: #fff3cd; border: 1px solid #ffe69c; padding: .75rem 1rem; border-radius: 4px; }
                  .confidence { font-weight: bold; }
                  em { color: #a33; font-style: normal; font-size: .85em; }
                </style>
                </head>
                <body>
                <h1>🔴 Build Failure Analysis</h1>
                %s<h2>Root Cause</h2>
                <p>%s</p>
                <h2>Evidence</h2>
                <ul>
                %s</ul>
                <h2>Likely Fix</h2>
                <p>%s</p>
                <h2>Confidence</h2>
                <p class="confidence">%d%%</p>
                </body>
                </html>
                """.formatted(banner, escape(result.rootCause()), evidenceItems, escape(result.suggestedFix()), result.confidence());
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
