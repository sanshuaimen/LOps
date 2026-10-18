package com.cifa.tool;

import com.cifa.evidence.Evidence;
import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.llm.ToolSchema;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Answers "does this test have a history of failing without related code
 * changes, passing on rerun, or failing intermittently?" against an
 * independent history data source. Without data it answers NO_DATA — it never
 * substitutes git history for flaky evidence.
 */
public final class FlakyTestHistoryTool implements InvestigationTool {

    public static final String NAME = "flaky_history";

    private final EvidenceCatalog catalog;
    private final FlakyHistorySource source;

    public FlakyTestHistoryTool(EvidenceCatalog catalog, FlakyHistorySource source) {
        this.catalog = catalog;
        this.source = source;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Query the independent test-history source for a test: pass/fail counts, rerun recoveries, "
                + "and failures with no related code change. Returns NO_DATA when no history exists — "
                + "that is a definitive answer, not an error.";
    }

    @Override
    public ToolSchema schema() {
        JSONObject properties = new JSONObject()
                .put("testName", new JSONObject()
                        .put("type", "string")
                        .put("description", "Fully qualified test class or class.method name"));
        JSONObject parameters = new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", List.of("testName"));
        return new ToolSchema(NAME, description(), parameters);
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        long started = System.nanoTime();
        String testName;
        try {
            testName = ToolArgs.requireString(arguments, "testName");
        } catch (IllegalArgumentException e) {
            return ToolResult.invalidArgument(e.getMessage());
        }

        var history = source.load(testName);
        if (history.isEmpty()) {
            return ToolResult.noData("no recorded history for test '" + testName + "'");
        }

        FlakyRunHistory runs = history.get();
        StringBuilder content = new StringBuilder();
        content.append("test: ").append(runs.testName()).append('\n');
        content.append("recorded runs: ").append(runs.runs().size())
                .append(" (passed ").append(runs.passCount())
                .append(", failed ").append(runs.failCount()).append(")\n");
        content.append("rerun recoveries: ").append(runs.rerunRecoveryCount()).append('\n');
        content.append("failures without related code change: ").append(runs.unrelatedFailureCount()).append('\n');
        content.append(runs.flakyEvidence()
                ? "verdict: FLAKY_EVIDENCE (history supports intermittent failure)\n"
                : "verdict: NO_FLAKY_EVIDENCE\n");
        runs.runs().forEach(run -> content.append("  build ").append(run.buildId())
                .append(": ").append(run.passed() ? "PASS" : "FAIL")
                .append(run.rerunPassed() ? " (rerun passed)" : "")
                .append(run.relatedCommit().isEmpty() ? "" : " (related " + run.relatedCommit() + ")")
                .append('\n'));

        Evidence evidence = catalog.register(EvidenceType.FLAKY_HISTORY, "flaky-history:" + testName,
                content.toString(), NAME);
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return ToolResult.ok(content.toString(), List.of(evidence.id()), elapsed);
    }
}
