package com.cifa.eval;

import com.cifa.agent.InvestigationBudget;
import com.cifa.agent.InvestigationState;
import com.cifa.context.ContextBudget;
import com.cifa.llm.LLMProvider;
import com.cifa.service.AgentDiagnosisService;
import com.cifa.service.BaselineFailureAnalyzer;
import com.cifa.service.BaselineResult;
import com.cifa.service.DiagnosisRequest;
import com.cifa.tool.FlakyHistorySource;
import com.cifa.tool.JsonFlakyHistorySource;
import com.cifa.trace.InMemoryTraceRecorder;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

public final class EvalRunner {
    private final BaselineFailureAnalyzer baseline;

    public EvalRunner() {
        this(new BaselineFailureAnalyzer());
    }

    public EvalRunner(BaselineFailureAnalyzer baseline) {
        this.baseline = baseline;
    }

    public EvalReport runBaseline(List<GoldenCase> cases, LLMProvider provider) {
        List<EvalResult> results = new ArrayList<>();
        for (GoldenCase goldenCase : cases) {
            long started = System.nanoTime();
            try {
                BaselineResult result = baseline.analyze(
                        new DiagnosisRequest(goldenCase.logPath(), goldenCase.repoRoot(), ContextBudget.DEFAULT), provider);
                long elapsed = elapsedSince(started);
                EvalObservation observation = new EvalObservation(
                        result.preparation().classified().category().name(),
                        result.analysis().rootCause(),
                        result.analysis().evidence(),
                        result.analysis().suggestedFix(),
                        result.preparation().classified().category().name().equals("UNKNOWN"),
                        List.of(), List.of(), 0, 0, elapsed, "COMPLETED", "");
                results.add(evaluate(goldenCase, observation));
            } catch (Exception e) {
                results.add(evaluate(goldenCase, EvalObservation.failure(elapsedSince(started), safeMessage(e))));
            }
        }
        return new EvalReport("baseline", "p0-v1", Instant.now(), results);
    }

    /**
     * Runs the agent path over the corpus. Each case gets its own provider
     * (typically a per-case recorded script), catalog, and trace; tool calls
     * and evidence citations flow into the standard scoring rules.
     */
    public EvalReport runAgent(List<GoldenCase> cases, Function<GoldenCase, LLMProvider> providers) {
        return runAgent(cases, providers, "eval-fake");
    }

    public EvalReport runAgent(List<GoldenCase> cases, Function<GoldenCase, LLMProvider> providers,
                               String modelName) {
        AgentDiagnosisService service = new AgentDiagnosisService();
        int availableTools = service.toolCount();
        List<EvalResult> results = new ArrayList<>();
        for (GoldenCase goldenCase : cases) {
            long started = System.nanoTime();
            try {
                InMemoryTraceRecorder trace = new InMemoryTraceRecorder();
                var output = service.investigate(
                        new DiagnosisRequest(goldenCase.logPath(), goldenCase.repoRoot(), ContextBudget.DEFAULT),
                        providers.apply(goldenCase), modelName, InvestigationBudget.defaults(),
                        "eval-" + goldenCase.id(), trace, flakyHistoryFor(goldenCase));
                long elapsed = elapsedSince(started);

                if (output.diagnosis() == null) {
                    results.add(evaluate(goldenCase,
                            EvalObservation.failure(elapsed, "model failure; stopReason " + output.state().stopReason())));
                    continue;
                }
                List<String> evidence = output.diagnosis().evidenceIds().stream()
                        .map(id -> output.catalog().find(id))
                        .flatMap(java.util.Optional::stream)
                        .map(item -> item.id() + " " + item.locator() + "\n" + summarize(item.content()))
                        .toList();
                List<String> toolCalls = output.state().toolCalls().stream()
                        .map(call -> call.toolName())
                        .toList();
                List<String> signatures = output.state().toolCalls().stream()
                        .map(InvestigationState.ToolCallRecord::argumentsSignature)
                        .toList();
                EvalObservation observation = new EvalObservation(
                        output.diagnosis().category(),
                        output.diagnosis().rootCause(),
                        evidence,
                        output.diagnosis().suggestedAction(),
                        output.diagnosis().unknown(),
                        toolCalls, signatures, availableTools, output.state().tokensUsed(), elapsed,
                        output.state().stopReason().name(), "");
                results.add(evaluate(goldenCase, observation));
            } catch (Exception e) {
                results.add(evaluate(goldenCase, EvalObservation.failure(elapsedSince(started), safeMessage(e))));
            }
        }
        return new EvalReport("agent", "p0-v1", Instant.now(), results);
    }

    private static FlakyHistorySource flakyHistoryFor(GoldenCase goldenCase) {
        Path historyFile = goldenCase.logPath().getParent().resolve("flaky-history.json");
        return JsonFlakyHistorySource.loadOrEmpty(historyFile);
    }

    private static String summarize(String content) {
        return content.length() <= 2000 ? content : content.substring(0, 2000) + "...[truncated]";
    }

    public EvalResult evaluate(GoldenCase goldenCase, EvalObservation observation) {
        String actualCategory = observation.category();
        boolean unknownCorrect = goldenCase.shouldBeUnknown() == observation.unknown();
        boolean categoryCorrect = goldenCase.expectedCategory().name().equals(actualCategory);
        String diagnosisText = (observation.rootCause() + " " + observation.suggestedAction())
                .toLowerCase(Locale.ROOT);
        boolean forbiddenFree = goldenCase.forbiddenConclusions().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .noneMatch(diagnosisText::contains);
        boolean rootCauseCorrect;
        if (goldenCase.shouldBeUnknown()) {
            rootCauseCorrect = observation.unknown();
        } else {
            rootCauseCorrect = !observation.unknown() && goldenCase.rootCauseKeywords().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .allMatch(observation.rootCause().toLowerCase(Locale.ROOT)::contains);
        }
        String evidenceText = String.join("\n", observation.evidence()).toLowerCase(Locale.ROOT);
        boolean evidenceCorrect = goldenCase.evidenceLocators().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .allMatch(evidenceText::contains);
        boolean evidenceSufficient = observation.unknown()
                ? goldenCase.shouldBeUnknown()
                : evidenceCorrect && (!observation.evidence().isEmpty() || goldenCase.evidenceLocators().isEmpty());
        int irrelevant = 0;
        Set<String> relevant = new HashSet<>(goldenCase.relevantTools());
        for (String tool : observation.toolCalls()) {
            if (!relevant.isEmpty() && !relevant.contains(tool)) {
                irrelevant++;
            }
        }
        // Invalid repeats follow the plan definition: same tool AND same arguments with
        // no new information. Distinct-argument calls of the same tool are legitimate.
        List<String> repeatKeys = observation.toolCallSignatures().isEmpty()
                ? observation.toolCalls()
                : observation.toolCallSignatures();
        int repeated = 0;
        Set<String> seen = new HashSet<>();
        for (String key : repeatKeys) {
            if (!seen.add(key)) {
                repeated++;
            }
        }
        boolean fullSweep = observation.availableToolCount() > 0
                && observation.toolCalls().size() >= observation.availableToolCount();
        // Path-sensitive acceptance: a case that declares required tools has NOT passed
        // if the investigation never invoked them, no matter how good the final answer is.
        boolean requiredToolsSatisfied = goldenCase.requiredTools().isEmpty()
                || observation.toolCalls().containsAll(goldenCase.requiredTools());
        return new EvalResult(
                goldenCase.id(), goldenCase.expectedCategory().name(), actualCategory, goldenCase.layer(),
                categoryCorrect, rootCauseCorrect, evidenceCorrect, evidenceSufficient,
                unknownCorrect, forbiddenFree, requiredToolsSatisfied, observation.toolCalls(), fullSweep,
                irrelevant, repeated, observation.tokenCount(), observation.elapsedMillis(),
                observation.stopReason(), observation.error());
    }

    private static long elapsedSince(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }
}
