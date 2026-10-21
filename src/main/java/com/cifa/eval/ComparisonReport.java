package com.cifa.eval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

    private static final String REAL_MODEL_DISCLAIMER = """
            > **Scope of this report:** REAL model runs — these numbers are specific to
            > the provider/model named in the header and its decoding parameters; they
            > are NOT comparable across models without re-running the same corpus.
            """;

    private static String disclaimerFor(String provider) {
        return provider.startsWith("fake") ? DISCLAIMER : REAL_MODEL_DISCLAIMER;
    }

    private final EvalReport baseline;
    private final EvalReport agent;
    private final ReportMetadata metadata;

    public ComparisonReport(EvalReport baseline, EvalReport agent) {
        this(baseline, agent, ReportMetadata.adhoc());
    }

    public ComparisonReport(EvalReport baseline, EvalReport agent, ReportMetadata metadata) {
        this.baseline = baseline;
        this.agent = agent;
        this.metadata = metadata;
    }

    public String renderMarkdown() {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Baseline vs Agent comparison report\n\n");
        markdown.append("generated: ").append(InstantIso.format(metadata.generatedAt())).append('\n');
        markdown.append("dataset: golden fixture corpus (").append(baseline.results().size()).append(" cases)\n");
        markdown.append("dataset version: ").append(metadata.datasetVersion()).append('\n');
        markdown.append("code version: ").append(metadata.codeVersion()).append('\n');
        markdown.append("provider: ").append(metadata.provider()).append('\n');
        markdown.append("run id: ").append(metadata.runId()).append('\n');
        markdown.append("prompt/config version: ").append(baseline.version()).append("\n\n");
        markdown.append(disclaimerFor(metadata.provider())).append('\n');

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

        markdown.append("## Per-layer breakdown\n\n");
        markdown.append("| layer | cases (baseline/agent) | passed (b/a) | category acc (b/a) | root cause acc (b/a) | evidence acc (b/a) | required-tools (b/a) |\n");
        markdown.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (String layer : layers()) {
            EvalReport baseLayer = baseline.filterByLayer(layer);
            EvalReport agentLayer = agent.filterByLayer(layer);
            markdown.append("| ").append(layer)
                    .append(" | ").append(baseLayer.results().size()).append("/").append(agentLayer.results().size())
                    .append(" | ").append(baseLayer.passedCount()).append("/").append(agentLayer.passedCount())
                    .append(" | ").append(pct(baseLayer.categoryAccuracy())).append("%/").append(pct(agentLayer.categoryAccuracy())).append('%')
                    .append(" | ").append(pct(baseLayer.rootCauseAccuracy())).append("%/").append(pct(agentLayer.rootCauseAccuracy())).append('%')
                    .append(" | ").append(pct(baseLayer.evidenceAccuracy())).append("%/").append(pct(agentLayer.evidenceAccuracy())).append('%')
                    .append(" | ").append(pct(baseLayer.requiredToolsSatisfiedRate())).append("%/").append(pct(agentLayer.requiredToolsSatisfiedRate())).append('%')
                    .append(" |\n");
        }
        markdown.append('\n');

        markdown.append(renderPayoffSection());

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
        return write(directory, fileStem + ".md");
    }

    /** Versioned name: comparison-<datasetVersion>-<provider>-<runId>.md */
    public Path writeTo(Path directory) {
        return write(directory, metadata.fileName());
    }

    private Path write(Path directory, String fileName) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(fileName);
            Files.writeString(file, renderMarkdown(), StandardCharsets.UTF_8);
            return file;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("unable to write comparison report", e);
        }
    }

    /**
     * §六.4: the report must state where the agent pays off and where it does not —
     * negative conclusions are first-class. Only deterministic quantities qualify:
     * accuracy and token cost. Latency is excluded (wall-clock varies between
     * identical fake runs and would break the reproducibility contract); timings
     * live in the JSON report.
     */
    private String renderPayoffSection() {
        StringBuilder markdown = new StringBuilder();
        markdown.append("## Where the agent pays off — and where it does not\n\n");
        markdown.append("| category | b: cat acc | a: cat acc | b: tokens/case | a: tokens/case | verdict |\n");
        markdown.append("| --- | --- | --- | --- | --- | --- |\n");
        List<String> noBenefit = new java.util.ArrayList<>();
        for (String category : categories()) {
            EvalReport baseCat = filterByCategory(baseline, category);
            EvalReport agentCat = filterByCategory(agent, category);
            double baseAcc = baseCat.categoryAccuracy();
            double agentAcc = agentCat.categoryAccuracy();
            double baseTokens = baseCat.avgTokenCount();
            double agentTokens = agentCat.avgTokenCount();
            String verdict = verdict(baseAcc, agentAcc, baseTokens, agentTokens);
            if (verdict.startsWith("no ") || verdict.startsWith("baseline")) {
                noBenefit.add(category);
            }
            markdown.append("| ").append(category)
                    .append(" | ").append(pct(baseAcc)).append("% | ").append(pct(agentAcc)).append('%')
                    .append(" | ").append(fmt0(baseTokens)).append(" | ").append(fmt0(agentTokens))
                    .append(" | ").append(verdict)
                    .append(" |\n");
        }
        markdown.append('\n');
        if (!noBenefit.isEmpty()) {
            markdown.append("**No measured agent benefit in: ").append(String.join(", ", noBenefit))
                    .append("** — the baseline matches or beats the agent there, at equal or lower ")
                    .append("token cost; the agent must earn its cost per category, not in aggregate.\n\n");
        }
        return markdown.toString();
    }

    private static String verdict(double baseAcc, double agentAcc, double baseTokens, double agentTokens) {
        if (agentAcc > baseAcc) {
            return String.format(java.util.Locale.ROOT, "pays off (+%.0fpp accuracy, %.1fx tokens)",
                    (agentAcc - baseAcc) * 100, baseTokens == 0 ? 0 : agentTokens / baseTokens);
        }
        if (agentAcc < baseAcc) {
            return "no benefit: baseline scored higher here";
        }
        if (agentTokens <= baseTokens) {
            return "no accuracy delta, no token cost";
        }
        return String.format(java.util.Locale.ROOT, "no measured accuracy benefit; agent costs %.1fx tokens here",
                baseTokens == 0 ? 0 : agentTokens / baseTokens);
    }

    private List<String> categories() {
        List<String> categories = new java.util.ArrayList<>();
        for (EvalResult result : baseline.results()) {
            if (!categories.contains(result.expectedCategory())) {
                categories.add(result.expectedCategory());
            }
        }
        for (EvalResult result : agent.results()) {
            if (!categories.contains(result.expectedCategory())) {
                categories.add(result.expectedCategory());
            }
        }
        return categories;
    }

    private static EvalReport filterByCategory(EvalReport report, String category) {
        return new EvalReport(report.subject() + "/" + category, report.version(), report.createdAt(),
                report.results().stream().filter(result -> result.expectedCategory().equals(category)).toList());
    }

    private static String fmt0(double value) {
        return String.format(java.util.Locale.ROOT, "%.0f", value);
    }

    private record GoldenCaseId(String id) {
    }

    private List<GoldenCaseId> caseIds() {
        return baseline.results().stream()
                .map(result -> new GoldenCaseId(result.caseId()))
                .toList();
    }

    /** Layer order follows the baseline report (first seen), then any agent-only layers. */
    private List<String> layers() {
        List<String> layers = new java.util.ArrayList<>(baseline.layers());
        for (String layer : agent.layers()) {
            if (!layers.contains(layer)) {
                layers.add(layer);
            }
        }
        return layers;
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
        private static String format(Instant instant) {
            return instant.atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'"));
        }
    }
}
