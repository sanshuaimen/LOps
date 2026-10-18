package com.cifa.eval;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record EvalReport(String subject, String version, Instant createdAt, List<EvalResult> results) {
    public EvalReport {
        results = List.copyOf(results);
    }

    public int passedCount() {
        return (int) results.stream().filter(EvalResult::passed).count();
    }

    public double categoryAccuracy() {
        return rate(results.stream().filter(EvalResult::categoryCorrect).count());
    }

    public double rootCauseAccuracy() {
        return rate(results.stream().filter(EvalResult::rootCauseCorrect).count());
    }

    public double evidenceAccuracy() {
        return rate(results.stream().filter(EvalResult::evidenceCorrect).count());
    }

    public double reasonableUnknownRate() {
        return rate(results.stream().filter(EvalResult::unknownCorrect).count());
    }

    public double fullSweepRate() {
        return rate(results.stream().filter(EvalResult::fullSweep).count());
    }

    public double irrelevantToolCallRate() {
        int calls = results.stream().mapToInt(result -> result.toolCalls().size()).sum();
        int irrelevant = results.stream().mapToInt(EvalResult::irrelevantToolCalls).sum();
        return calls == 0 ? 0.0 : (double) irrelevant / calls;
    }

    public double invalidRepeatRate() {
        int calls = results.stream().mapToInt(result -> result.toolCalls().size()).sum();
        int repeated = results.stream().mapToInt(EvalResult::repeatedToolCalls).sum();
        return calls == 0 ? 0.0 : (double) repeated / calls;
    }

    public double forbiddenFreeRate() {
        return rate(results.stream().filter(EvalResult::forbiddenConclusionFree).count());
    }

    public double requiredToolsSatisfiedRate() {
        return rate(results.stream().filter(EvalResult::requiredToolsSatisfied).count());
    }

    public double avgTokenCount() {
        return results.isEmpty() ? 0.0 : (double) totalTokens() / results.size();
    }

    public long totalTokens() {
        return results.stream().mapToLong(EvalResult::tokenCount).sum();
    }

    public long totalElapsedMillis() {
        return results.stream().mapToLong(EvalResult::elapsedMillis).sum();
    }

    public Map<String, Integer> toolPathDistribution() {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (EvalResult result : results) {
            String path = String.join("->", result.toolCalls());
            distribution.merge(path.isEmpty() ? "(none)" : path, 1, Integer::sum);
        }
        return distribution;
    }

    public String toJson() {
        JSONObject json = new JSONObject();
        json.put("subject", subject);
        json.put("version", version);
        json.put("createdAt", createdAt.toString());
        json.put("fakeOnly", true);
        JSONObject metrics = new JSONObject();
        metrics.put("total", results.size());
        metrics.put("passed", passedCount());
        metrics.put("categoryAccuracy", categoryAccuracy());
        metrics.put("rootCauseAccuracy", rootCauseAccuracy());
        metrics.put("evidenceAccuracy", evidenceAccuracy());
        metrics.put("reasonableUnknownRate", reasonableUnknownRate());
        metrics.put("fullSweepRate", fullSweepRate());
        metrics.put("irrelevantToolCallRate", irrelevantToolCallRate());
        metrics.put("invalidRepeatRate", invalidRepeatRate());
        metrics.put("totalTokens", totalTokens());
        metrics.put("totalElapsedMillis", totalElapsedMillis());
        metrics.put("toolPathDistribution", new JSONObject(toolPathDistribution()));
        json.put("metrics", metrics);
        JSONArray cases = new JSONArray();
        results.forEach(result -> cases.put(result.toJson()));
        json.put("results", cases);
        return json.toString(2);
    }

    public String toMarkdown() {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Evaluation: ").append(subject).append("\n\n");
        markdown.append("Version: ").append(version).append("; created: ").append(createdAt).append("\n\n");
        markdown.append("Fake responses only demonstrate the execution path; they do not represent real model performance.\n\n");
        markdown.append("- Cases: ").append(results.size()).append(", passed: ").append(passedCount()).append('\n');
        markdown.append("- Category accuracy: ").append(format(categoryAccuracy())).append('\n');
        markdown.append("- Root-cause accuracy: ").append(format(rootCauseAccuracy())).append('\n');
        markdown.append("- Evidence accuracy: ").append(format(evidenceAccuracy())).append('\n');
        markdown.append("- Reasonable UNKNOWN rate: ").append(format(reasonableUnknownRate())).append('\n');
        markdown.append("- Full-sweep rate: ").append(format(fullSweepRate())).append('\n');
        markdown.append("- Irrelevant tool-call rate: ").append(format(irrelevantToolCallRate())).append('\n');
        markdown.append("- Invalid repeat rate: ").append(format(invalidRepeatRate())).append('\n');
        markdown.append("- Tokens: ").append(totalTokens()).append(", elapsed ms: ").append(totalElapsedMillis()).append("\n\n");
        markdown.append("| Case | Expected | Actual | Passed | Tools |\n| --- | --- | --- | --- | --- |\n");
        for (EvalResult result : results) {
            markdown.append("| ").append(result.caseId()).append(" | ").append(result.expectedCategory())
                    .append(" | ").append(result.actualCategory()).append(" | ").append(result.passed())
                    .append(" | ").append(String.join(" -> ", result.toolCalls())).append(" |\n");
        }
        return markdown.toString();
    }

    private double rate(long count) {
        return results.isEmpty() ? 0.0 : (double) count / results.size();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
