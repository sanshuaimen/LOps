package com.cifa.app;

import com.cifa.evidence.Evidence;
import com.cifa.evidence.EvidenceCatalog;
import com.cifa.persistence.entity.DiagnosisTaskEntity;
import com.cifa.persistence.entity.EvidenceEntity;
import com.cifa.persistence.entity.InvestigationStepEntity;
import com.cifa.persistence.mapper.DiagnosisTaskMapper;
import com.cifa.persistence.mapper.EvidenceMapper;
import com.cifa.persistence.mapper.InvestigationStepMapper;
import com.cifa.task.TaskStateMachine;
import com.cifa.task.TaskStatus;
import com.cifa.trace.TraceEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Persistence boundary for task outcomes. Lives in its own bean so the
 * transaction is applied through the Spring proxy — calling @Transactional
 * methods via `this.` inside the executor would silently skip it.
 *
 * Steps + evidence + terminal status move in ONE transaction: no row can be
 * "finished but still RUNNING" (P1 plan, task 4).
 */
@Component
public class DiagnosisTaskStore {

    private final DiagnosisTaskMapper taskMapper;
    private final InvestigationStepMapper stepMapper;
    private final EvidenceMapper evidenceMapper;

    public DiagnosisTaskStore(DiagnosisTaskMapper taskMapper,
                              InvestigationStepMapper stepMapper,
                              EvidenceMapper evidenceMapper) {
        this.taskMapper = taskMapper;
        this.stepMapper = stepMapper;
        this.evidenceMapper = evidenceMapper;
    }

    @Transactional
    public void persistOutcome(DiagnosisTaskEntity task, TaskStatus terminal, String finalDiagnosis,
                               String category, String stopReason, String errorMessage,
                               List<TraceEvent> events, EvidenceCatalog catalog) {
        for (TraceEvent event : events) {
            InvestigationStepEntity step = new InvestigationStepEntity();
            step.setTaskId(task.getId());
            step.setSequence((int) event.sequence());
            step.setRound(event.round());
            step.setEventType(event.eventType());
            step.setModel(event.model());
            step.setPromptVersion(event.promptVersion());
            step.setHypothesis(TraceEvent.safe(event.hypothesis()));
            step.setToolName(event.toolName());
            step.setArgumentsSummary(TraceEvent.safe(event.argumentsSummary()));
            step.setEvidenceIds(event.evidenceIds());
            step.setTokenCount(event.tokenCount());
            step.setElapsedMillis(event.elapsedMillis());
            step.setStatus(event.status());
            step.setStopReason(event.stopReason());
            step.setError(TraceEvent.safe(event.error()));
            stepMapper.insert(step);
        }
        if (catalog != null) {
            for (Evidence evidence : catalog.all()) {
                EvidenceEntity row = new EvidenceEntity();
                row.setTaskId(task.getId());
                row.setEvidenceId(evidence.id());
                row.setType(evidence.type().name());
                row.setLocator(evidence.locator());
                row.setContent(evidence.content());
                row.setContentHash(evidence.contentHash());
                row.setToolName(evidence.toolName());
                row.setStartLine(evidence.startLine());
                row.setEndLine(evidence.endLine());
                row.setCommitId(evidence.commit());
                evidenceMapper.insert(row);
            }
        }

        TaskStateMachine.requireTransition(TaskStatus.RUNNING, terminal);
        task.setStatus(terminal.name());
        task.setFinalDiagnosis(finalDiagnosis);
        task.setCategory(category);
        task.setStopReason(stopReason);
        task.setErrorMessage(errorMessage == null ? "" : errorMessage);
        task.setFinishedAt(OffsetDateTime.now());
        taskMapper.updateById(task);
    }

    /** Guarded transition for PENDING -> RUNNING at dispatch time. */
    @Transactional
    public void markRunning(DiagnosisTaskEntity task) {
        TaskStateMachine.requireTransition(TaskStatus.valueOf(task.getStatus()), TaskStatus.RUNNING);
        task.setStatus(TaskStatus.RUNNING.name());
        taskMapper.updateById(task);
    }

    /** Land a FAILED outcome from whichever non-terminal state the row is in. */
    @Transactional
    public void failFromCurrentStatus(DiagnosisTaskEntity task, String stopReason, String errorMessage) {
        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        TaskStateMachine.requireTransition(current, TaskStatus.FAILED);
        task.setStatus(TaskStatus.FAILED.name());
        task.setStopReason(stopReason);
        task.setErrorMessage(errorMessage == null ? "" : errorMessage);
        task.setFinishedAt(OffsetDateTime.now());
        taskMapper.updateById(task);
    }
}
