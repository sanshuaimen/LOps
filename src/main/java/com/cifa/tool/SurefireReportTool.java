package com.cifa.tool;

import com.cifa.evidence.Evidence;
import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.llm.ToolSchema;
import com.cifa.parsing.ParsedFailure;
import com.cifa.parsing.SurefireReportParser;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Answers "which tests failed, with which exception, stack and module?" by
 * parsing Surefire/Failsafe XML reports directly. Independent of the console
 * log tools; read-only.
 */
public final class SurefireReportTool implements InvestigationTool {

    public static final String NAME = "surefire_report";
    private static final int MAX_CONTENT_CHARS = 8000;

    private final EvidenceCatalog catalog;
    private final Path repoRoot;

    public SurefireReportTool(EvidenceCatalog catalog, Path repoRoot) {
        this.catalog = catalog;
        this.repoRoot = repoRoot;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Parse Surefire/Failsafe XML test reports in the repository and list failing tests "
                + "with exception type, message and stack excerpt. Does not depend on the console log.";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.object(NAME, description());
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        long started = System.nanoTime();
        ParsedFailure parsed = SurefireReportParser.parse(repoRoot);
        if (parsed == null) {
            return ToolResult.noData("no failing tests found in Surefire/Failsafe XML reports");
        }

        StringBuilder content = new StringBuilder();
        content.append("failing tests: ").append(String.join(", ", parsed.failingTests())).append('\n');
        content.append("exception: ").append(parsed.exceptionType()).append('\n');
        content.append("message: ").append(parsed.message()).append('\n');
        parsed.frames().forEach(frame -> content.append("  at ")
                .append(frame.className()).append('.')
                .append(frame.methodName())
                .append(frame.fileName().isEmpty() ? "" : "(" + frame.fileName() + ":" + frame.lineNumber() + ")")
                .append('\n'));
        String text = content.toString();
        if (text.length() > MAX_CONTENT_CHARS) {
            text = text.substring(0, MAX_CONTENT_CHARS) + "\n...[truncated]";
        }

        Evidence evidence = catalog.register(EvidenceType.TEST_REPORT, "surefire-reports", text, NAME);
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return ToolResult.ok(text, List.of(evidence.id()), elapsed);
    }
}
