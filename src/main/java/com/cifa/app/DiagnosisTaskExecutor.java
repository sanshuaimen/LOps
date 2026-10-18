package com.cifa.app;

import com.cifa.agent.InvestigationBudget;
import com.cifa.agent.StructuredDiagnosis;
import com.cifa.context.ContextBudget;
import com.cifa.evidence.EvidenceCatalog;
import com.cifa.llm.AnalysisResult;
import com.cifa.llm.LLMProvider;
import com.cifa.persistence.entity.DiagnosisTaskEntity;
import com.cifa.service.AgentDiagnosisService;
import com.cifa.service.BaselineFailureAnalyzer;
import com.cifa.service.BaselineResult;
import com.cifa.service.DiagnosisRequest;
import com.cifa.task.TaskStateMachine;
import com.cifa.task.TaskStatus;
import com.cifa.trace.InMemoryTraceRecorder;
import com.cifa.trace.TraceEvent;
import com.cifa.tool.FlakyHistorySource;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Bridges the framework-free diagnosis services into the database: state
 * transitions per the P1-2 mappings, then a single-transaction outcome persist.
 * The executor itself holds no transaction — see {@link DiagnosisTaskStore}.
 *
 * MODELS: the executor never builds a real provider — it receives whatever
 * LLMProvider the server config assembled (tests: fake only).
 */
@Component
public class DiagnosisTaskExecutor {

    private final DiagnosisTaskStore store;
    private final AgentDiagnosisService agentDiagnosisService;
    private final BaselineFailureAnalyzer baselineFailureAnalyzer;
    private final FlakyHistorySource flakyHistory;

    public DiagnosisTaskExecutor(DiagnosisTaskStore store,
                                 AgentDiagnosisService agentDiagnosisService,
                                 BaselineFailureAnalyzer baselineFailureAnalyzer,
                                 FlakyHistorySource flakyHistory) {
        this.store = store;
        this.agentDiagnosisService = agentDiagnosisService;
        this.baselineFailureAnalyzer = baselineFailureAnalyzer;
        this.flakyHistory = flakyHistory;
    }

    /**
     * Runs one accepted task. Never throws for expected failure modes — every
     * outcome (including infrastructure IOExceptions) lands in a terminal
     * status. InterruptedException (cancellation) lands in CANCELLED without
     * a diagnosis. Safe to invoke from a worker Runnable.
     */
    public TaskStatus run(DiagnosisTaskEntity task, String logContent, Path repoRoot,
                          LLMProvider llm, String modelName) {
        store.markRunning(task);

        Path logFile = null;
        try {
            logFile = Files.createTempFile("diagnosis-task-" + task.getId() + "-", ".log");
            Files.writeString(logFile, logContent == null ? "" : logContent);
            DiagnosisRequest request = new DiagnosisRequest(logFile, repoRoot, ContextBudget.DEFAULT);
            return "baseline".equals(task.getMode())
                    ? runBaseline(task, request, llm)
                    : runAgent(task, request, llm, modelName);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            store.persistOutcome(task, TaskStatus.CANCELLED, null, "", "CANCELLED",
                    "cancelled before completion", List.of(), null);
            return TaskStatus.CANCELLED;
        } catch (IOException | RuntimeException failure) {
            store.persistOutcome(task, TaskStatus.FAILED, null, "", "EXECUTION_ERROR",
                    safeMessage(failure), List.of(), null);
            return TaskStatus.FAILED;
        } finally {
            if (logFile != null) {
                try {
                    Files.deleteIfExists(logFile);
                } catch (IOException cleanupFailure) {
                    // temp dir cleanup; not worth failing a finished task over
                }
            }
        }
    }

    private TaskStatus runAgent(DiagnosisTaskEntity task, DiagnosisRequest request,
                                LLMProvider llm, String modelName) throws IOException, InterruptedException {
        InMemoryTraceRecorder trace = new InMemoryTraceRecorder();
        var output = agentDiagnosisService.investigate(request, llm, modelName,
                InvestigationBudget.defaults(), task.getId(), trace, flakyHistory);

        TaskStatus terminal = TaskStateMachine.terminalForAgent(output.state().stopReason());
        StructuredDiagnosis diagnosis = output.diagnosis();
        // MODEL_FAILURE keeps diagnosis null on purpose (must not become an UNKNOWN row).
        store.persistOutcome(task, terminal,
                diagnosis == null ? null : diagnosis.toJson().toString(),
                diagnosis == null ? "" : diagnosis.category(),
                output.state().stopReason().name(),
                terminal == TaskStatus.FAILED ? "model failure after retries" : "",
                trace.events(), output.catalog());
        return terminal;
    }

    private TaskStatus runBaseline(DiagnosisTaskEntity task, DiagnosisRequest request,
                                   LLMProvider llm) throws IOException, InterruptedException {
        try {
            BaselineResult result = baselineFailureAnalyzer.analyze(request, llm);
            // Baseline has no controlled evidence catalog; evidence rows are agent-only.
            AnalysisResult analysis = result.analysis();
            String diagnosisJson = new JSONObject()
                    .put("rootCause", analysis.rootCause())
                    .put("evidence", analysis.evidence())
                    .put("suggestedFix", analysis.suggestedFix())
                    .put("confidence", analysis.confidence())
                    .put("unverified", result.unverifiedEvidence())
                    .toString();
            store.persistOutcome(task, TaskStateMachine.terminalForBaseline(true),
                    diagnosisJson,
                    result.preparation().classified().category().name(),
                    "BASELINE_COMPLETED", "", List.of(), null);
            return TaskStatus.SUCCEEDED;
        } catch (IOException executionFailure) {
            store.persistOutcome(task, TaskStateMachine.terminalForBaseline(false), null, "",
                    "BASELINE_FAILED", safeMessage(executionFailure), List.of(), null);
            return TaskStatus.FAILED;
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }
}
