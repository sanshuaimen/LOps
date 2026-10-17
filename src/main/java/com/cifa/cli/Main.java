package com.cifa.cli;

import com.cifa.classification.ClassifiedFailure;
import com.cifa.classification.FailureCategory;
import com.cifa.classification.FailureClassifier;
import com.cifa.config.ConfigFile;
import com.cifa.config.ConfigResolver;
import com.cifa.context.AnalysisContext;
import com.cifa.context.ContextBudget;
import com.cifa.context.ContextBuilder;
import com.cifa.correlation.CorrelatedCommit;
import com.cifa.correlation.GitCorrelator;
import com.cifa.ingestion.LogNormalizer;
import com.cifa.llm.AnalysisResult;
import com.cifa.llm.AnthropicProvider;
import com.cifa.llm.EvidenceValidator;
import com.cifa.llm.LLMProvider;
import com.cifa.llm.OllamaProvider;
import com.cifa.parsing.FailureLogParser;
import com.cifa.parsing.ParsedFailure;
import com.cifa.parsing.StackFrame;
import com.cifa.parsing.SurefireReportParser;
import com.cifa.report.HtmlRenderer;
import com.cifa.report.MarkdownRenderer;
import com.cifa.report.ReportRenderer;
import com.cifa.report.SlackRenderer;
import com.cifa.integration.GitHubCommentPoster;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Main {

    public static void main(String[] args) throws Exception {
        String logPath = null;
        String repoPath = null;
        String formatFlag = null;
        String repoSlugFlag = null;
        String prNumberFlag = null;
        boolean postComment = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--log" -> logPath = ++i < args.length ? args[i] : null;
                case "--repo" -> repoPath = ++i < args.length ? args[i] : null;
                case "--format" -> formatFlag = ++i < args.length ? args[i] : null;
                case "--repo-slug" -> repoSlugFlag = ++i < args.length ? args[i] : null;
                case "--pr-number" -> prNumberFlag = ++i < args.length ? args[i] : null;
                case "--post-comment" -> postComment = true;
                default -> { }
            }
        }
        if (logPath == null || repoPath == null) {
            System.err.println("Usage: analyze --log <path> --repo <path> [--format markdown|html|slack] "
                    + "[--post-comment [--repo-slug owner/repo] [--pr-number N]]");
            System.exit(1);
            return;
        }

        Map<String, String> fileConfig = ConfigFile.load(Path.of("."));

        String rawLog = Files.readString(Path.of(logPath));
        String normalized = LogNormalizer.normalize(rawLog);

        Path repoRoot = Path.of(repoPath);
        ParsedFailure xmlFailure = SurefireReportParser.parse(repoRoot);
        ParsedFailure failure;
        if (xmlFailure != null) {
            System.err.println("Using Surefire/Failsafe XML report(s) found under " + repoRoot + "; ignoring --log content.");
            failure = xmlFailure;
        } else {
            failure = FailureLogParser.parse(normalized);
        }
        ClassifiedFailure classified = FailureClassifier.classify(failure);

        CorrelatedCommit commit = null;
        String sourceSnippet = "";
        List<String> correlationNotes = new ArrayList<>();
        if (classified.category() != FailureCategory.INFRA_FLAKY) {
            StackFrame projectFrame = GitCorrelator.firstProjectFrame(failure.frames());
            if (projectFrame != null) {
                Optional<Path> filePath = GitCorrelator.resolveFilePathAcrossModules(repoRoot, projectFrame);
                if (filePath.isPresent()) {
                    commit = GitCorrelator.correlate(repoRoot, filePath.get(), projectFrame.lineNumber());
                    sourceSnippet = ContextBuilder.readSourceSnippet(filePath.get(), projectFrame.lineNumber());
                } else {
                    correlationNotes.add("source file could not be located for " + projectFrame.className());
                }
            }
        }

        String contextBudgetStr = ConfigResolver.resolve(null, System.getenv("CONTEXT_CHAR_BUDGET"), fileConfig, "CONTEXT_CHAR_BUDGET", null);
        ContextBudget budget = parseContextBudget(contextBudgetStr);
        AnalysisContext context = ContextBuilder.build(classified, commit, sourceSnippet, correlationNotes, budget);
        if (!context.excludedNotes().isEmpty()) {
            System.err.println("Context notes: " + String.join("; ", context.excludedNotes()));
        }

        String provider = ConfigResolver.resolve(null, System.getenv("LLM_PROVIDER"), fileConfig, "LLM_PROVIDER", "ollama");
        LLMProvider llm = switch (provider) {
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
            default -> throw new IllegalArgumentException(
                    "Unsupported LLM_PROVIDER '" + provider + "' — supported values: ollama, anthropic");
        };

        AnalysisResult result = llm.analyze(context);
        List<String> unverified = EvidenceValidator.findUnverified(result, context);

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
