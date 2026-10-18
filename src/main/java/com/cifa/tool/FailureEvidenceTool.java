package com.cifa.tool;

import com.cifa.evidence.Evidence;
import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.llm.ToolSchema;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Answers "what direct failure evidence does the console log contain?" by
 * reading the normalized log by keyword and/or line range. Read-only; every
 * excerpt becomes LOG evidence in the task catalog.
 */
public final class FailureEvidenceTool implements InvestigationTool {

    public static final String NAME = "failure_evidence";
    private static final int MAX_MATCH_LINES = 100;
    private static final int MAX_LOG_LINES = 20_000;

    private final EvidenceCatalog catalog;
    private final String[] logLines;

    public FailureEvidenceTool(EvidenceCatalog catalog, String normalizedLog) {
        this.catalog = catalog;
        this.logLines = splitLines(normalizedLog);
    }

    private static String[] splitLines(String normalizedLog) {
        if (normalizedLog == null || normalizedLog.isEmpty()) {
            return new String[0];
        }
        String[] lines = normalizedLog.split("\n", MAX_LOG_LINES);
        return lines;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Read direct failure evidence from the normalized console log. "
                + "Filter by keyword and/or 1-based line range. Returns numbered log lines.";
    }

    @Override
    public ToolSchema schema() {
        JSONObject properties = new JSONObject()
                .put("keyword", new JSONObject()
                        .put("type", "string")
                        .put("description", "Case-insensitive text to search for in the log"))
                .put("startLine", new JSONObject().put("type", "integer").put("description", "1-based start line"))
                .put("endLine", new JSONObject().put("type", "integer").put("description", "1-based end line (inclusive)"));
        JSONObject parameters = new JSONObject()
                .put("type", "object")
                .put("properties", properties);
        return new ToolSchema(NAME, description(), parameters);
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            return doExecute(arguments);
        } catch (IllegalArgumentException e) {
            return ToolResult.invalidArgument(e.getMessage());
        }
    }

    private ToolResult doExecute(Map<String, Object> arguments) {
        String keyword = ToolArgs.optionalString(arguments, "keyword")
                .map(text -> text.toLowerCase(Locale.ROOT))
                .orElse("");
        int startLine = ToolArgs.optionalInt(arguments, "startLine").orElse(1);
        int endLine = ToolArgs.optionalInt(arguments, "endLine").orElse(logLines.length);
        if (startLine < 1) {
            return ToolResult.invalidArgument("startLine must be >= 1");
        }
        if (endLine < startLine) {
            return ToolResult.invalidArgument("endLine must be >= startLine");
        }
        if (startLine > logLines.length) {
            return ToolResult.noData("log has " + logLines.length + " lines; range " + startLine + "-" + endLine + " is empty");
        }

        List<String> matched = new ArrayList<>();
        int firstMatchedLine = -1;
        int lastMatchedLine = -1;
        int upper = Math.min(endLine, logLines.length);
        for (int index = startLine; index <= upper && matched.size() < MAX_MATCH_LINES; index++) {
            String line = logLines[index - 1];
            if (!keyword.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(keyword)) {
                continue;
            }
            if (firstMatchedLine < 0) {
                firstMatchedLine = index;
            }
            lastMatchedLine = index;
            matched.add(index + ": " + line);
        }

        if (matched.isEmpty()) {
            return ToolResult.noData(keyword.isEmpty()
                    ? "no log lines in range " + startLine + "-" + endLine
                    : "no log lines matching keyword '" + keyword + "'");
        }

        String content = String.join("\n", matched);
        Evidence evidence = catalog.register(EvidenceType.LOG, "normalized_log:" + firstMatchedLine + "-" + lastMatchedLine,
                content, NAME, firstMatchedLine, lastMatchedLine, "");
        return ToolResult.ok(content, List.of(evidence.id()), 0);
    }
}
