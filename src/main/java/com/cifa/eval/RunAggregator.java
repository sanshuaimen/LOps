package com.cifa.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregates N runs of the same subject over the same corpus (--runs N):
 * mean and population stddev per case (tokens, elapsed, pass count) and per
 * expected category (accuracy, tokens per case). Accuracy numbers under fake
 * runs are deterministic; the variance tables exist to prove that and to catch
 * flaky pipelines when real providers are plugged in.
 */
public final class RunAggregator {

    private final List<EvalReport> runs;

    public RunAggregator(List<EvalReport> runs) {
        if (runs.isEmpty()) {
            throw new IllegalArgumentException("no runs to aggregate");
        }
        String subject = runs.get(0).subject();
        List<String> caseIds = caseIds(runs.get(0));
        for (EvalReport run : runs) {
            if (!run.subject().equals(subject)) {
                throw new IllegalArgumentException("mixed subjects: " + subject + " vs " + run.subject());
            }
            if (!caseIds(run).equals(caseIds)) {
                throw new IllegalArgumentException("runs cover different case sets; --runs must replay one corpus");
            }
        }
        this.runs = List.copyOf(runs);
    }

    public int runCount() {
        return runs.size();
    }

    public record CaseStats(String caseId, double meanTokens, double tokenStddev,
                            double meanElapsedMillis, double elapsedStddev, int passCount) {
    }

    public record CategoryStats(String expectedCategory, int caseCount,
                                double meanCategoryAccuracy, double categoryAccuracyStddev,
                                double meanTokensPerCase, double tokensPerCaseStddev) {
    }

    /** Case order follows the first run. */
    public List<CaseStats> perCase() {
        List<CaseStats> stats = new ArrayList<>();
        for (String caseId : caseIds(runs.get(0))) {
            List<EvalResult> series = series(caseId);
            stats.add(new CaseStats(caseId,
                    mean(doubles(series, EvalResult::tokenCount)),
                    stddev(doubles(series, EvalResult::tokenCount)),
                    mean(doubles(series, EvalResult::elapsedMillis)),
                    stddev(doubles(series, EvalResult::elapsedMillis)),
                    (int) series.stream().filter(EvalResult::passed).count()));
        }
        return stats;
    }

    /** Category order follows first sight in the first run. */
    public List<CategoryStats> perCategory() {
        Map<String, Integer> caseCountByCategory = new LinkedHashMap<>();
        runs.get(0).results().forEach(result ->
                caseCountByCategory.merge(result.expectedCategory(), 1, Integer::sum));
        List<CategoryStats> stats = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : caseCountByCategory.entrySet()) {
            List<Double> accuracies = new ArrayList<>();
            List<Double> tokensPerCase = new ArrayList<>();
            for (EvalReport run : runs) {
                List<EvalResult> inCategory = run.results().stream()
                        .filter(result -> result.expectedCategory().equals(entry.getKey()))
                        .toList();
                accuracies.add(rate(inCategory, EvalResult::categoryCorrect));
                tokensPerCase.add(mean(doubles(inCategory, EvalResult::tokenCount)));
            }
            stats.add(new CategoryStats(entry.getKey(), entry.getValue(),
                    mean(accuracies), stddev(accuracies),
                    mean(tokensPerCase), stddev(tokensPerCase)));
        }
        return stats;
    }

    public String renderMarkdown() {
        StringBuilder markdown = new StringBuilder();
        markdown.append("## Run aggregation (--runs ").append(runs.size()).append(")\n\n");
        markdown.append("Mean ± population stddev over ").append(runs.size()).append(" runs.\n\n");
        markdown.append("### Per category\n\n");
        markdown.append("| category | cases | category accuracy | tokens/case |\n| --- | --- | --- | --- |\n");
        for (CategoryStats stats : perCategory()) {
            markdown.append("| ").append(stats.expectedCategory())
                    .append(" | ").append(stats.caseCount())
                    .append(" | ").append(fmt(stats.meanCategoryAccuracy())).append(" ± ").append(fmt(stats.categoryAccuracyStddev()))
                    .append(" | ").append(fmt(stats.meanTokensPerCase())).append(" ± ").append(fmt(stats.tokensPerCaseStddev()))
                    .append(" |\n");
        }
        markdown.append("\n### Per case\n\n");
        markdown.append("| case | tokens | elapsed ms | passed (of N) |\n| --- | --- | --- | --- |\n");
        for (CaseStats stats : perCase()) {
            markdown.append("| ").append(stats.caseId())
                    .append(" | ").append(fmt(stats.meanTokens())).append(" ± ").append(fmt(stats.tokenStddev()))
                    .append(" | ").append(fmt(stats.meanElapsedMillis())).append(" ± ").append(fmt(stats.elapsedStddev()))
                    .append(" | ").append(stats.passCount()).append("/").append(runs.size())
                    .append(" |\n");
        }
        return markdown.toString();
    }

    private List<EvalResult> series(String caseId) {
        return runs.stream()
                .map(run -> byCase(run, caseId))
                .toList();
    }

    private static EvalResult byCase(EvalReport run, String caseId) {
        return run.results().stream()
                .filter(result -> result.caseId().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("run missing case " + caseId));
    }

    private static List<String> caseIds(EvalReport run) {
        return run.results().stream().map(EvalResult::caseId).toList();
    }

    private static List<Double> doubles(List<EvalResult> results, java.util.function.ToDoubleFunction<EvalResult> read) {
        return results.stream().map(result -> read.applyAsDouble(result)).toList();
    }

    private static double rate(List<EvalResult> results, java.util.function.Predicate<EvalResult> flag) {
        if (results.isEmpty()) {
            return 0.0;
        }
        return (double) results.stream().filter(flag).count() / results.size();
    }

    /** Population stddev (divide by N): the full run set is the population. */
    static double stddev(List<Double> values) {
        double mean = mean(values);
        double squared = 0.0;
        for (double value : values) {
            squared += (value - mean) * (value - mean);
        }
        return Math.sqrt(squared / values.size());
    }

    static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
