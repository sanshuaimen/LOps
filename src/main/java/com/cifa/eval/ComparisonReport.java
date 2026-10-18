package com.cifa.eval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders the versioned Baseline-vs-Agent comparison report. Rule-scored
 * metrics only; the mandatory disclaimer states that fake/recorded model runs
 * prove the pipeline, not real-world performance.
 */
public final class ComparisonReport {

    private static final String DISCLAIMER = """
            > **Scope of this report:** model runs use Fake / recorded responses.
            > They prove the pipeline (tools, evidence, trace, scoring) end to end;
            > they do NOT represent real model performance. All accuracy numbers
            > below must be re-measured with real models before being quoted.
            """;

    private final EvalReport baseline;
    private final EvalReport agent;

    public ComparisonReport(EvalReport baseline, EvalReport agent) {
        this.baseline = baseline;
        this.agent = agent;
    }

    public String renderMarkdown() {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Baseline vs Agent comparison report\n\n");
        markdown.append("generated: ").append(InstantIso.now()).append('\n');
        markdown.append("dataset: golden fixture corpus (").append(baseline.results().size()).append(" cases)\n");
        markdown.append("prompt/config version: ").append(baseline.version()).append("\n\n");
        markdown.append(DISCLAIMER).append('\n');

        markdown.append("## Aggregate metrics\n\n");
        markdown.append("| metric | baseline | agent |\n| --- | --- | --- |\n");
        markdown.append("| cases | ").append(baseline.results().size()).append(" | ").append(agent.results().size()).append(" |\n");
        markdown.append("| passed (all rule checks) | ").append(baseline.passedCount()).append(" | ").append(agent.passedCount()).append(" |\n");
        markdown.append(String.format("| category accuracy | %.1f%% | %.1f%%%n",
                pct(baseline.categoryAccuracy()), pct(agent.categoryAccuracy())));
        markdown.append(String.format("| root cause accuracy | %.1f%% | %.1f%%%n",
                pct(baseline.rootCauseAccuracy()), pct(agent.rootCauseAccuracy())));
        markdown.append(String.format("| evidence locator accuracy | %.1f%% | %.1f%%%n",
                pct(baseline.evidenceAccuracy()), pct(agent.evidenceAccuracy())));
        markdown.append(String.format("| unknown behavior correctness | %.1f%% | %.1f%%%n",
                pct(baseline.reasonableUnknownRate()), pct(agent.reasonableUnknownRate())));
        markdown.append(String.format("| forbidden-conclusion-free | %.1f%% | %.1f%%%n",
                pct(baseline.forbiddenFreeRate()), pct(agent.forbiddenFreeRate())));
        markdown.append(String.format("| required-tools satisfied (path-sensitive compliance) | %.1f%% | %.1f%%%n",
                pct(baseline.requiredToolsSatisfiedRate()), pct(agent.requiredToolsSatisfiedRate())));
        markdown.append(String.format("| full-sweep rate (called every tool) | %.1f%% | %.1f%%%n",
                pct(baseline.fullSweepRate()), pct(agent.fullSweepRate())));
        markdown.append(String.format("| irrelevant tool-call rate | %.1f%% | %.1f%%%n",
                pct(baseline.irrelevantToolCallRate()), pct(agent.irrelevantToolCallRate())));
        markdown.append(String.format("| repeated tool-call rate | %.1f%% | %.1f%%%n",
                pct(baseline.invalidRepeatRate()), pct(agent.invalidRepeatRate())));
        markdown.append(String.format("| avg tokens per case | %.0f | %.0f%n",
                baseline.avgTokenCount(), agent.avgTokenCount()));
        markdown.append('\n');

        markdown.append("## Tool-path distribution (agent)\n\n");
        agent.toolPathDistribution().forEach((path, count) ->
                markdown.append("- ").append(count).append(" × `").append(path).append("`\n"));
        markdown.append('\n');

        markdown.append("## Per-case results\n\n");
        markdown.append("| case | baseline: cat/rc/ev | agent: cat/rc/ev | agent stop | agent tools |\n");
        markdown.append("| --- | --- | --- | --- | --- |\n");
        for (GoldenCaseId id : caseIds()) {
            EvalResult base = byCase(baseline, id.id());
            EvalResult ag = byCase(agent, id.id());
            markdown.append("| ").append(id.id())
                    .append(" | ").append(tri(base))
                    .append(" | ").append(tri(ag))
                    .append(" | ").append(ag == null ? "-" : ag.stopReason())
                    .append(" | ").append(ag == null || ag.toolCalls().isEmpty() ? "-" : String.join(",", ag.toolCalls()))
                    .append(" |\n");
        }
        markdown.append("\n`cat/rc/ev` = categoryCorrect / rootCauseCorrect / evidenceCorrect (1/0)\n");
        return markdown.toString();
    }

    public Path writeTo(Path directory, String fileStem) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(fileStem + ".md");
            Files.writeString(file, renderMarkdown(), StandardCharsets.UTF_8);
            return file;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("unable to write comparison report", e);
        }
    }

    private record GoldenCaseId(String id) {
    }

    private List<GoldenCaseId> caseIds() {
        return baseline.results().stream()
                .map(result -> new GoldenCaseId(result.caseId()))
                .toList();
    }

    private static EvalResult byCase(EvalReport report, String caseId) {
        return report.results().stream()
                .filter(result -> result.caseId().equals(caseId))
                .findFirst()
                .orElse(null);
    }

    private static String tri(EvalResult result) {
        if (result == null) {
            return "-/-/-";
        }
        return flag(result.categoryCorrect()) + "/" + flag(result.rootCauseCorrect()) + "/" + flag(result.evidenceCorrect());
    }

    private static String flag(boolean value) {
        return value ? "1" : "0";
    }

    private static double pct(double rate) {
        return rate * 100.0;
    }

    private static final class InstantIso {
        private static final String now() {
            return java.time.Instant.now().atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'"));
        }
    }
}
