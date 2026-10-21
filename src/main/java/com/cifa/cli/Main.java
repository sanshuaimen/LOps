package com.cifa.cli;

import com.cifa.classification.ClassifiedFailure;
import com.cifa.config.ConfigFile;
import com.cifa.config.ConfigResolver;
import com.cifa.context.AnalysisContext;
import com.cifa.context.ContextBudget;
import com.cifa.integration.GitHubCommentPoster;
import com.cifa.llm.AnalysisResult;
import com.cifa.llm.AnthropicProvider;
import com.cifa.llm.EvidenceValidator;
import com.cifa.llm.LLMProvider;
import com.cifa.llm.OllamaProvider;
import com.cifa.report.HtmlRenderer;
import com.cifa.report.MarkdownRenderer;
import com.cifa.report.ReportRenderer;
import com.cifa.report.SlackRenderer;
import com.cifa.service.DiagnosisRequest;
import com.cifa.service.DiagnosisTaskService;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class Main {

    public static void main(String[] args) throws Exception {
        String logPath = null;
        String repoPath = null;
        String formatFlag = null;
        String repoSlugFlag = null;
        String prNumberFlag = null;
        String modeFlag = null;
        String traceFlag = null;
        String fakeScriptFlag = null;
        String flakyHistoryFlag = null;
        String taskIdFlag = null;
        String providerFlag = null;
        boolean postComment = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--log" -> logPath = ++i < args.length ? args[i] : null;
                case "--repo" -> repoPath = ++i < args.length ? args[i] : null;
                case "--format" -> formatFlag = ++i < args.length ? args[i] : null;
                case "--repo-slug" -> repoSlugFlag = ++i < args.length ? args[i] : null;
                case "--pr-number" -> prNumberFlag = ++i < args.length ? args[i] : null;
                case "--post-comment" -> postComment = true;
                case "--mode" -> modeFlag = ++i < args.length ? args[i] : null;
                case "--trace" -> traceFlag = ++i < args.length ? args[i] : null;
                case "--fake-script" -> fakeScriptFlag = ++i < args.length ? args[i] : null;
                case "--flaky-history" -> flakyHistoryFlag = ++i < args.length ? args[i] : null;
                case "--task-id" -> taskIdFlag = ++i < args.length ? args[i] : null;
                case "--provider" -> providerFlag = ++i < args.length ? args[i] : null;
                default -> { }
            }
        }
        if (logPath == null || repoPath == null) {
            System.err.println("Usage: analyze --log <path> --repo <path> [--mode baseline|agent] [--trace <file>] "
                    + "[--fake-script <file>] [--flaky-history <file>] [--task-id <id>] "
                    + "[--format markdown|html|slack] [--post-comment [--repo-slug owner/repo] [--pr-number N]]");
            System.exit(1);
            return;
        }

        Map<String, String> fileConfig = ConfigFile.load(Path.of("."));

        String contextBudgetStr = ConfigResolver.resolve(null, System.getenv("CONTEXT_CHAR_BUDGET"), fileConfig, "CONTEXT_CHAR_BUDGET", null);
        ContextBudget budget = parseContextBudget(contextBudgetStr);
        Path repoRoot = Path.of(repoPath);
        var prepared = new com.cifa.service.DiagnosisTaskService().prepare(
                new com.cifa.service.DiagnosisRequest(Path.of(logPath), repoRoot, budget));
        if (prepared.usedXmlReport()) {
            System.err.println("Using Surefire/Failsafe XML report(s) found under " + repoRoot + "; ignoring --log content.");
        }
        ClassifiedFailure classified = prepared.classified();
        AnalysisContext context = prepared.context();
        if (!context.excludedNotes().isEmpty()) {
            System.err.println("Context notes: " + String.join("; ", context.excludedNotes()));
        }

        String provider = ConfigResolver.resolve(providerFlag, System.getenv("LLM_PROVIDER"), fileConfig, "LLM_PROVIDER", "ollama");
        String fakeScript = ConfigResolver.resolve(fakeScriptFlag, System.getenv("FAKE_SCRIPT"), fileConfig, "FAKE_SCRIPT", null);
        LLMProvider llm = switch (provider) {
            case "fake" -> fakeScript != null
                    ? com.cifa.llm.FakeLLMProvider.recorded(Path.of(fakeScript))
                    : new com.cifa.llm.FakeLLMProvider();
            case "ollama" -> {
                String model = ConfigResolver.resolve(null, System.getenv("OLLAMA_MODEL"), fileConfig, "OLLAMA_MODEL", "deepseek-r1:8b");
                yield new OllamaProvider("http://localhost:11434", model);
            }
            case "anthropic" -> {
                String apiKey = System.getenv("ANTHROPIC_API_KEY");
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalStateException(
                            "ANTHROPIC_API_KEY environment variable is required for LLM_PROVIDER=anthropic");
                }
                String model = ConfigResolver.resolve(null, System.getenv("ANTHROPIC_MODEL"), fileConfig, "ANTHROPIC_MODEL", "claude-sonnet-5");
                yield new AnthropicProvider(apiKey, model);
            }
            case "deepseek" -> {
                String apiKey = System.getenv("DEEPSEEK_API_KEY");
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalStateException(
                            "DEEPSEEK_API_KEY environment variable is required for LLM_PROVIDER=deepseek");
                }
                String model = ConfigResolver.resolve(null, System.getenv("DEEPSEEK_MODEL"), fileConfig, "DEEPSEEK_MODEL", com.cifa.llm.DeepSeekProvider.DEFAULT_MODEL);
                yield new com.cifa.llm.DeepSeekProvider(apiKey, model);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported LLM_PROVIDER '" + provider + "' — supported values: fake, ollama, anthropic, deepseek");
        };

        String mode = ConfigResolver.resolve(modeFlag, System.getenv("DIAGNOSIS_MODE"), fileConfig, "DIAGNOSIS_MODE", "baseline");
        if (mode.equals("agent")) {
            runAgentMode(llm, provider, Path.of(logPath), repoRoot, budget,
                    traceFlag, flakyHistoryFlag, taskIdFlag, fileConfig);
            return;
        }

        com.cifa.service.BaselineResult baseline = new com.cifa.service.BaselineFailureAnalyzer().analyze(prepared, llm);
        AnalysisResult result = baseline.analysis();
        List<String> unverified = baseline.unverifiedEvidence();

        String format = ConfigResolver.resolve(formatFlag, System.getenv("DEFAULT_FORMAT"), fileConfig, "DEFAULT_FORMAT", "markdown");
        ReportRenderer renderer = switch (format) {
            case "markdown" -> new MarkdownRenderer();
            case "html" -> new HtmlRenderer();
            case "slack" -> new SlackRenderer();
            default -> throw new IllegalArgumentException(
                    "Unsupported --format '" + format + "' — supported values: markdown, html, slack");
        };
        System.out.println(renderer.render(classified.category(), result, unverified));

        if (postComment) {
            postGitHubComment(classified, result, unverified, repoSlugFlag, prNumberFlag);
        }
    }

    private static void runAgentMode(LLMProvider llm, String providerName, Path logPath, Path repoRoot,
                                     ContextBudget budget, String traceFlag, String flakyHistoryFlag,
                                     String taskIdFlag, Map<String, String> fileConfig) throws Exception {
        String taskId = taskIdFlag != null ? taskIdFlag
                : "local-" + Long.toString(System.currentTimeMillis(), 36);
        Path traceFile = Path.of(traceFlag != null ? traceFlag : "agent-trace-" + taskId + ".jsonl");
        com.cifa.tool.FlakyHistorySource flakyHistory = com.cifa.tool.JsonFlakyHistorySource.empty();
        if (flakyHistoryFlag != null) {
            flakyHistory = new com.cifa.tool.JsonFlakyHistorySource(Path.of(flakyHistoryFlag));
        }

        String modelName = providerName.equals("fake") ? "fake" : ConfigResolver.resolve(
                null, System.getenv("AGENT_MODEL"), fileConfig, "AGENT_MODEL", providerName + "-default");
        var agentBudget = com.cifa.agent.InvestigationBudget.defaults();

        try (com.cifa.trace.JsonlTraceRecorder traceRecorder = new com.cifa.trace.JsonlTraceRecorder(traceFile)) {
            var output = new com.cifa.service.AgentDiagnosisService().investigate(
                    new com.cifa.service.DiagnosisRequest(logPath, repoRoot, budget),
                    llm, modelName, agentBudget, taskId, traceRecorder, flakyHistory);

            if (output.diagnosis() == null) {
                // MODEL_FAILURE keeps a null diagnosis on purpose — it must not be
                // dressed up as an UNKNOWN. Report the structured failure and exit non-zero.
                System.out.println(new org.json.JSONObject()
                        .put("taskId", taskId)
                        .put("stopReason", output.state().stopReason().name())
                        .put("diagnosis", org.json.JSONObject.NULL)
                        .put("evidenceCount", output.catalog().size())
                        .put("trace", traceFile.toAbsolutePath().toString())
                        .toString(2));
                System.err.println("Agent investigation failed: " + output.state().stopReason()
                        + " (trace: " + traceFile.toAbsolutePath() + ")");
                System.exit(2);
                return;
            }

            System.out.println("== Agent diagnosis (" + taskId + ") ==");
            System.out.println(output.diagnosis().toJson().toString(2));
            System.out.println("trace: " + traceFile.toAbsolutePath());
        }
    }

    private static void postGitHubComment(ClassifiedFailure classified, AnalysisResult result,
                                           List<String> unverified, String repoSlugFlag, String prNumberFlag) {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("--post-comment requires GITHUB_TOKEN to be set; skipping comment post.");
            return;
        }
        String repoSlug = repoSlugFlag != null ? repoSlugFlag : System.getenv("GITHUB_REPOSITORY");
        Integer prNumber = prNumberFlag != null ? parsePrNumber(prNumberFlag)
                : GitHubCommentPoster.prNumberFromEventPath(System.getenv("GITHUB_EVENT_PATH"));
        if (repoSlug == null || prNumber == null) {
            System.err.println("--post-comment requires a repo slug and PR number "
                    + "(via --repo-slug/--pr-number or GITHUB_REPOSITORY/GITHUB_EVENT_PATH); skipping comment post.");
            return;
        }
        String commentBody = new MarkdownRenderer().render(classified.category(), result, unverified);
        try {
            new GitHubCommentPoster(token, HttpClient.newHttpClient()).postComment(repoSlug, prNumber, commentBody);
        } catch (Exception e) {
            System.err.println("Failed to post PR comment: " + e.getMessage());
        }
    }

    private static Integer parsePrNumber(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ContextBudget parseContextBudget(String value) {
        if (value == null) {
            return ContextBudget.DEFAULT;
        }
        try {
            int chars = Integer.parseInt(value);
            if (chars <= 0) {
                throw new NumberFormatException("non-positive CONTEXT_CHAR_BUDGET");
            }
            return new ContextBudget(ContextBudget.DEFAULT.maxFrames(), chars, chars);
        } catch (NumberFormatException e) {
            System.err.println("Ignoring invalid CONTEXT_CHAR_BUDGET value '" + value + "'; using default.");
            return ContextBudget.DEFAULT;
        }
    }
}
