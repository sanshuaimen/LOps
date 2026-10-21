package com.cifa.cli;

import com.cifa.eval.CodeVersion;
import com.cifa.eval.ComparisonReport;
import com.cifa.eval.DatasetVersion;
import com.cifa.eval.EvalReport;
import com.cifa.eval.EvalRunner;
import com.cifa.eval.GoldenCase;
import com.cifa.eval.ReportMetadata;
import com.cifa.eval.RunAggregator;
import com.cifa.llm.AnthropicProvider;
import com.cifa.llm.FakeLLMProvider;
import com.cifa.llm.LLMProvider;
import com.cifa.llm.OllamaProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 7: the real-model evaluation entry (local manual command, never CI —
 * CI stays all-fake). Runs baseline + agent over a corpus layer N times and
 * writes the versioned comparison report (plus per-run JSON and --runs
 * aggregation). Credentials come from the environment only and are checked
 * BEFORE any case runs: a missing key is a clear exit-2 error, not a
 * mid-corpus crash.
 *
 * Usage: eval [--fixtures <dir>] --provider fake|ollama|anthropic
 *             [--model <model>] [--runs N]
 *             [--layer accept|regression|robustness|security|all]
 *             [--out <dir>]
 */
public final class EvalCommand {

    private static final String USAGE = """
            eval — golden-corpus evaluation runner (baseline vs agent)

            Usage: eval [--fixtures <dir>] --provider fake|ollama|anthropic [--model <model>]
                        [--runs N] [--layer accept|regression|robustness|security|all]
                        [--out <dir>]

            Defaults: --fixtures fixtures/eval  --runs 1  --layer accept  --out target/eval-reports
            Provider defaults: ollama → OLLAMA_URL (default http://localhost:11434) + OLLAMA_MODEL
                               (default deepseek-r1:8b); anthropic → ANTHROPIC_API_KEY + ANTHROPIC_MODEL
                               (default claude-sonnet-5).
            Credentials come from the environment only; nothing is stored in the repo.
            --provider fake never touches the network and is what CI uses.
            """;

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, System.getenv()));
    }

    public static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err,
                          Map<String, String> env) {
        Options options;
        try {
            options = parse(args);
        } catch (UsageException e) {
            err.println("eval: " + e.getMessage() + "\n\n" + USAGE);
            return 2;
        }
        if (options.help) {
            out.print(USAGE);
            return 0;
        }
        try {
            return execute(options, out, err, env);
        } catch (UsageException e) {
            err.println("eval: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            err.println("eval failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return 1;
        }
    }

    private static final class Options {
        Path fixtures = Path.of("fixtures/eval");
        String provider;
        String model;
        int runs = 1;
        String layer = "accept";
        Path out = Path.of("target/eval-reports");
        boolean help;
    }

    private static final class UsageException extends RuntimeException {
        private UsageException(String message) {
            super(message);
        }
    }

    private static Options parse(String[] args) {
        Options options = new Options();
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--help", "-h" -> options.help = true;
                case "--fixtures" -> options.fixtures = Path.of(value(args, ++i, flag));
                case "--provider" -> options.provider = value(args, ++i, flag);
                case "--model" -> options.model = value(args, ++i, flag);
                case "--runs" -> {
                    String raw = value(args, ++i, flag);
                    try {
                        options.runs = Integer.parseInt(raw);
                    } catch (NumberFormatException e) {
                        throw new UsageException("--runs expects an integer, got '" + raw + "'");
                    }
                    if (options.runs < 1) {
                        throw new UsageException("--runs must be >= 1, got " + options.runs);
                    }
                }
                case "--layer" -> options.layer = value(args, ++i, flag);
                case "--out" -> options.out = Path.of(value(args, ++i, flag));
                default -> throw new UsageException("unknown option '" + flag + "'");
            }
        }
        if (options.help) {
            return options;
        }
        if (options.provider == null) {
            throw new UsageException("--provider is required (fake|ollama|anthropic)");
        }
        if (!List.of("fake", "ollama", "anthropic").contains(options.provider)) {
            throw new UsageException("unsupported --provider '" + options.provider
                    + "' — supported values: fake, ollama, anthropic");
        }
        if (!options.layer.equals("all") && !GoldenCase.knownLayers().contains(options.layer)) {
            throw new UsageException("unknown --layer '" + options.layer
                    + "' — expected one of " + GoldenCase.knownLayers() + " or all");
        }
        return options;
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new UsageException(flag + " expects a value");
        }
        return args[index];
    }

    private static int execute(Options options, java.io.PrintStream out, java.io.PrintStream err,
                               Map<String, String> env) throws Exception {
        List<GoldenCase> allCases = GoldenCase.loadDirectory(options.fixtures);
        if (allCases.isEmpty()) {
            throw new UsageException("no golden cases found under " + options.fixtures);
        }
        List<GoldenCase> cases = options.layer.equals("all")
                ? allCases
                : allCases.stream().filter(c -> c.layer().equals(options.layer)).toList();
        if (cases.isEmpty()) {
            throw new UsageException("layer '" + options.layer + "' has no cases under " + options.fixtures);
        }

        String model = resolveModel(options, env);
        LLMProvider realProvider = switch (options.provider) {
            case "fake" -> null; // per-case scripted factory below
            case "ollama" -> {
                preflightOllama(env, err);
                yield new OllamaProvider(ollamaUrl(env), model);
            }
            case "anthropic" -> {
                String apiKey = env.get("ANTHROPIC_API_KEY");
                if (apiKey == null || apiKey.isBlank()) {
                    throw new UsageException("ANTHROPIC_API_KEY is required for --provider anthropic; "
                            + "credentials come from the environment (never flags, repo, or git)");
                }
                yield new AnthropicProvider(apiKey, model);
            }
            default -> throw new UsageException("unsupported provider " + options.provider);
        };
        String providerLabel = options.provider.equals("fake")
                ? "fake"
                : options.provider + "-" + model;
        String modelName = options.provider.equals("fake") ? "eval-fake" : model;

        EvalRunner runner = new EvalRunner();
        List<EvalReport> baselineRuns = new ArrayList<>();
        List<EvalReport> agentRuns = new ArrayList<>();
        List<Path> written = new ArrayList<>();
        for (int run = 1; run <= options.runs; run++) {
            LLMProvider baselineProvider = realProvider != null ? realProvider : new FakeLLMProvider();
            // Per-case provider factory; a real provider is shared across all cases.
            java.util.function.Function<GoldenCase, LLMProvider> agentProvider;
            if (realProvider != null) {
                agentProvider = ignoredCase -> realProvider;
            } else {
                agentProvider = EvalCommand::scriptedProviderFor;
            }
            ReportMetadata metadata = new ReportMetadata(
                    DatasetVersion.of(cases), CodeVersion.shortSha(), providerLabel,
                    UUID.randomUUID().toString().substring(0, 8), Instant.now());
            EvalReport baseline = runner.runBaseline(cases, baselineProvider);
            EvalReport agent = runner.runAgent(cases, agentProvider, modelName);
            baselineRuns.add(baseline);
            agentRuns.add(agent);

            String markdown = new ComparisonReport(baseline, agent, metadata).renderMarkdown();
            if (options.runs > 1 && run == options.runs) {
                markdown += "\n\n" + new RunAggregator(baselineRuns).renderMarkdown()
                        + "\n\n" + new RunAggregator(agentRuns).renderMarkdown();
            }
            Files.createDirectories(options.out);
            written.add(Files.writeString(options.out.resolve(metadata.fileName()), markdown, StandardCharsets.UTF_8));
            written.add(Files.writeString(options.out.resolve("evalreport-baseline-" + metadata.runId() + ".json"),
                    baseline.toJson(), StandardCharsets.UTF_8));
            written.add(Files.writeString(options.out.resolve("evalreport-agent-" + metadata.runId() + ".json"),
                    agent.toJson(), StandardCharsets.UTF_8));
            out.printf("run %d/%d: baseline passed %d/%d, agent passed %d/%d (category acc %.1f%% vs %.1f%%)%n",
                    run, options.runs, baseline.passedCount(), cases.size(),
                    agent.passedCount(), cases.size(),
                    agent.categoryAccuracy() * 100.0, baseline.categoryAccuracy() * 100.0);
        }

        out.println("eval complete: layer=" + options.layer + " cases=" + cases.size()
                + " provider=" + providerLabel + (options.provider.equals("fake") ? " (fake)" : " (REAL MODEL)"));
        written.forEach(path -> out.println("  " + path.toAbsolutePath()));
        return 0;
    }

    private static String resolveModel(Options options, Map<String, String> env) {
        if (options.model != null) {
            return options.model;
        }
        return switch (options.provider) {
            case "ollama" -> env.getOrDefault("OLLAMA_MODEL", "deepseek-r1:8b");
            case "anthropic" -> env.getOrDefault("ANTHROPIC_MODEL", "claude-sonnet-5");
            default -> "fake";
        };
    }

    private static String ollamaUrl(Map<String, String> env) {
        return env.getOrDefault("OLLAMA_URL", "http://localhost:11434");
    }

    /** Fail fast with a clear message when Ollama is not reachable — before any case runs. */
    private static void preflightOllama(Map<String, String> env, java.io.PrintStream err) {
        String url = ollamaUrl(env);
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/api/tags"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new UsageException("Ollama at " + url + " answered HTTP " + response.statusCode()
                        + " — start Ollama or point OLLAMA_URL at a healthy instance");
            }
        } catch (UsageException e) {
            throw e;
        } catch (Exception e) {
            err.println("eval: preflight could not reach Ollama at " + url + " (" + e.getClass().getSimpleName() + ")");
            throw new UsageException("Ollama not reachable at " + url
                    + " — start Ollama or set OLLAMA_URL; real-model runs are local manual commands (CI never does this)");
        }
    }

    /** Recorded agent-script.json replay when the case ships one; honest-UNKNOWN fake otherwise. */
    private static LLMProvider scriptedProviderFor(GoldenCase goldenCase) {
        Path script = goldenCase.logPath().getParent().resolve("agent-script.json");
        if (Files.exists(script)) {
            try {
                return FakeLLMProvider.recorded(script);
            } catch (Exception e) {
                throw new IllegalStateException("bad script for " + goldenCase.id(), e);
            }
        }
        return new FakeLLMProvider();
    }
}
