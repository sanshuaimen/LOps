package com.cifa.service;

import com.cifa.agent.InvestigationBudget;
import com.cifa.llm.AnalysisResult;
import com.cifa.llm.LLMProvider;
import com.cifa.tool.FlakyHistorySource;
import com.cifa.trace.TraceEvent;
import com.cifa.trace.TraceRecorder;

import java.io.IOException;
import java.util.List;

/**
 * 降级链（product-facing fallback chain): agent → baseline (same provider) → rules-only.
 *
 * <p>Control-plane/semantic-plane discipline: the FALLBACK DECISION lives here in
 * deterministic service code — an agent that already died never decides whether to
 * retry or degrade. The agent path is attempted once; a null diagnosis (only
 * MODEL_FAILURE produces one) triggers the baseline attempt with the same provider;
 * if the provider is broken outright, the deterministic rule classifier answers with
 * an explicitly stamped "no model root cause" result. Never silent: every degraded
 * outcome carries the stage and the categorized reason.</p>
 *
 * <p>InterruptedException always propagates — cancellation must not be converted
 * into a fallback. The EVAL pipeline deliberately does NOT route through this class
 * (eval measures the raw agent; the product path may degrade).</p>
 */
public final class FallbackDiagnosisOrchestrator {

    public enum Stage { AGENT, BASELINE_LLM, BASELINE_RULES }

    public record Outcome(
            AgentDiagnosisService.AgentOutput agent,
            Stage stage,
            String fallbackReason,
            BaselineResult baseline) {

        public boolean degraded() {
            return stage != Stage.AGENT;
        }
    }

    private final AgentDiagnosisService agentService;
    private final BaselineFailureAnalyzer baselineAnalyzer;
    private final com.cifa.service.DiagnosisTaskService taskService;

    public FallbackDiagnosisOrchestrator() {
        this(new AgentDiagnosisService(), new BaselineFailureAnalyzer(), new DiagnosisTaskService());
    }

    FallbackDiagnosisOrchestrator(AgentDiagnosisService agentService,
                                  BaselineFailureAnalyzer baselineAnalyzer,
                                  DiagnosisTaskService taskService) {
        this.agentService = agentService;
        this.baselineAnalyzer = baselineAnalyzer;
        this.taskService = taskService;
    }

    public Outcome diagnose(com.cifa.service.DiagnosisRequest request, LLMProvider llm, String modelName,
                            InvestigationBudget budget, String taskId, TraceRecorder trace,
                            FlakyHistorySource flakyHistory)
            throws IOException, InterruptedException {
        AgentDiagnosisService.AgentOutput agent;
        String reason;
        try {
            agent = agentService.investigate(request, llm, modelName, budget, taskId, trace, flakyHistory);
        } catch (IOException e) {
            // Provider/tool transport died outright — degrade just like MODEL_FAILURE.
            agent = null;
            reason = "agent attempt failed with I/O error: " + safeMessage(e);
            recordFallback(taskId, trace, reason, Stage.BASELINE_LLM);
            try {
                BaselineResult baseline = baselineAnalyzer.analyze(request, llm);
                return new Outcome(agent, Stage.BASELINE_LLM, reason, baseline);
            } catch (IOException ioAgain) {
                return rulesTail(request, trace, taskId, reason + "; baseline provider also failed ("
                        + safeMessage(ioAgain) + ")");
            }
        }
        if (agent.diagnosis() != null) {
            return new Outcome(agent, Stage.AGENT, null, null);
        }
        reason = "agent " + agent.state().stopReason() + "; " + lastFailureSummary(agent);
        recordFallback(taskId, trace, reason, Stage.BASELINE_LLM);
        try {
            BaselineResult baseline = baselineAnalyzer.analyze(request, llm);
            return new Outcome(agent, Stage.BASELINE_LLM, reason, baseline);
        } catch (IOException e) {
            // Provider is broken outright: deterministic rule-classifier tail.
            return rulesTail(request, trace, taskId, reason + "; baseline provider also failed ("
                    + safeMessage(e) + ")");
        }
    }

    /** Deterministic tail: the rule classifier always answers, no model involved. */
    private Outcome rulesTail(com.cifa.service.DiagnosisRequest request, TraceRecorder trace,
                              String taskId, String tailReason) throws IOException, InterruptedException {
        recordFallback(taskId, trace, tailReason, Stage.BASELINE_RULES);
        var prepared = taskService.prepare(request);
        AnalysisResult rulesOnly = new AnalysisResult(
                "Model unavailable (" + tailReason + "). Rule-engine fallback: the failure was "
                        + "classified deterministically, but no model root cause is available.",
                List.of(),
                "Re-run when the model provider is reachable, or investigate manually.",
                0,
                false);
        return new Outcome(null, Stage.BASELINE_RULES, tailReason,
                new BaselineResult(prepared, rulesOnly, List.of()));
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String lastFailureSummary(AgentDiagnosisService.AgentOutput agent) {
        return agent.traceEvents().stream()
                .filter(event -> "model_failure".equals(event.eventType()))
                .reduce((first, second) -> second)
                .map(TraceEvent::error)
                .filter(error -> error != null && !error.isEmpty())
                .map(error -> "last failure: " + error)
                .orElse("no categorized model failure recorded");
    }

    private void recordFallback(String taskId, TraceRecorder trace, String reason, Stage stage) {
        // The degrade decision is auditable like every other event; tracing must
        // never break the fallback chain itself.
        try {
            trace.record(new TraceEvent(taskId, 0, java.time.Instant.now(), "fallback", 0,
                    null, null, "degrade to " + stage, "", "", List.of(), 0, 0, "ok", "", reason));
        } catch (Exception ignored) {
            // tracing is best-effort on the fallback path
        }
    }
}
