package com.cifa.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cifa.llm.AgentDecision;
import com.cifa.llm.FakeLLMProvider;
import com.cifa.persistence.entity.DiagnosisTaskEntity;
import com.cifa.persistence.entity.EvidenceEntity;
import com.cifa.persistence.entity.InvestigationStepEntity;
import com.cifa.persistence.mapper.DiagnosisTaskMapper;
import com.cifa.persistence.mapper.EvidenceMapper;
import com.cifa.persistence.mapper.InvestigationStepMapper;
import com.cifa.service.AgentDiagnosisService;
import com.cifa.service.BaselineFailureAnalyzer;
import com.cifa.task.TaskStatus;
import com.cifa.tool.JsonFlakyHistorySource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-4 verification: executor terminal mappings, single-transaction persist,
 * trace->step and catalog->evidence fidelity.
 *
 * REQUIRES DOCKER — skipped otherwise; these assertions stay unverified until
 * a Docker-capable environment runs the suite.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class DiagnosisTaskExecutorTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DiagnosisTaskStore store;

    @Autowired
    private DiagnosisTaskMapper taskMapper;

    @Autowired
    private InvestigationStepMapper stepMapper;

    @Autowired
    private EvidenceMapper evidenceMapper;

    private DiagnosisTaskExecutor executor;
    private Path repoRoot;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DiagnosisTaskExecutor(store, new AgentDiagnosisService(),
                new BaselineFailureAnalyzer(), JsonFlakyHistorySource.empty());
        repoRoot = Files.createTempDirectory("executor-repo");
    }

    private DiagnosisTaskEntity newTask(String id, String mode) {
        DiagnosisTaskEntity task = new DiagnosisTaskEntity();
        task.setId(id);
        task.setStatus(TaskStatus.PENDING.name());
        task.setMode(mode);
        task.setRepoAlias("npe-log");
        task.setStopReason("");
        task.setErrorMessage("");
        taskMapper.insert(task);
        return task;
    }

    @Test
    void agentCompletionMapsToSucceededWithEvidenceAndSteps() throws Exception {
        DiagnosisTaskEntity task = newTask("exec-1", "agent");
        FakeLLMProvider provider = new FakeLLMProvider(
                AgentDecision.toolCall("failure_evidence", java.util.Map.of("keyword", "error"), "check log"),
                AgentDecision.submit("LOGIC_ERROR", "null customer", List.of("LOG-001"), 70, "guard"));

        TaskStatus terminal = executor.run(task, "1: [ERROR] boom\n", repoRoot, provider, "fake");

        assertEquals(TaskStatus.SUCCEEDED, terminal);
        DiagnosisTaskEntity reloaded = taskMapper.selectById("exec-1");
        assertEquals("SUCCEEDED", reloaded.getStatus());
        assertEquals("LOGIC_ERROR", reloaded.getCategory());
        assertEquals("COMPLETED", reloaded.getStopReason());
        assertTrue(reloaded.getFinalDiagnosis().contains("null customer"));
        // one tool_call step + model_decision steps; steps equal trace events
        List<InvestigationStepEntity> steps = stepMapper.selectList(
                new LambdaQueryWrapper<InvestigationStepEntity>()
                        .eq(InvestigationStepEntity::getTaskId, "exec-1")
                        .orderByAsc(InvestigationStepEntity::getSequence));
        assertEquals(4, steps.size(), "2 decisions + 1 tool call + 1 conclusion");
        // evidence: LOG-001 preprocessing + LOG-002 from the tool
        List<EvidenceEntity> evidence = evidenceMapper.selectList(
                new LambdaQueryWrapper<EvidenceEntity>().eq(EvidenceEntity::getTaskId, "exec-1"));
        assertEquals(2, evidence.size());
        assertTrue(evidence.stream().anyMatch(row -> row.getEvidenceId().equals("LOG-002")));
    }

    @Test
    void insufficientEvidenceMapsToUnknownNotFailed() throws Exception {
        DiagnosisTaskEntity task = newTask("exec-2", "agent");

        TaskStatus terminal = executor.run(task, "", repoRoot, new FakeLLMProvider(), "fake");

        assertEquals(TaskStatus.UNKNOWN, terminal);
        assertEquals("UNKNOWN", taskMapper.selectById("exec-2").getStatus());
        assertEquals("INSUFFICIENT_EVIDENCE", taskMapper.selectById("exec-2").getStopReason());
    }

    @Test
    void modelFailureMapsToFailedWithNullDiagnosis() throws Exception {
        DiagnosisTaskEntity task = newTask("exec-3", "agent");
        FakeLLMProvider provider = new FakeLLMProvider();
        provider.enqueueFailure(new java.io.IOException("api down"));
        provider.enqueueFailure(new java.io.IOException("api down"));
        provider.enqueueFailure(new java.io.IOException("api down"));

        TaskStatus terminal = executor.run(task, "log", repoRoot, provider, "fake");

        // plan red line: 模型持续异常属于 FAILED，不能伪装成 UNKNOWN
        assertEquals(TaskStatus.FAILED, terminal);
        DiagnosisTaskEntity reloaded = taskMapper.selectById("exec-3");
        assertEquals("FAILED", reloaded.getStatus());
        assertNull(reloaded.getFinalDiagnosis(), "MODEL_FAILURE must not carry a diagnosis");
        assertEquals("MODEL_FAILURE", reloaded.getStopReason());
    }

    @Test
    void baselineSuccessMapsToSucceededEvenWhenContentIsUnknown() throws Exception {
        DiagnosisTaskEntity task = newTask("exec-4", "baseline");

        TaskStatus terminal = executor.run(task, "", repoRoot, new FakeLLMProvider(), "fake");

        assertEquals(TaskStatus.SUCCEEDED, terminal);
        DiagnosisTaskEntity reloaded = taskMapper.selectById("exec-4");
        assertEquals("SUCCEEDED", reloaded.getStatus());
        assertEquals("BASELINE_COMPLETED", reloaded.getStopReason());
    }

    @Test
    void interruptionMapsToCancelledWithoutDiagnosis() throws Exception {
        DiagnosisTaskEntity task = newTask("exec-5", "agent");
        FakeLLMProvider provider = new FakeLLMProvider();
        provider.enqueueFailure(new InterruptedException("cancel requested"));

        TaskStatus terminal = executor.run(task, "log", repoRoot, provider, "fake");

        assertEquals(TaskStatus.CANCELLED, terminal);
        DiagnosisTaskEntity reloaded = taskMapper.selectById("exec-5");
        assertEquals("CANCELLED", reloaded.getStatus());
        assertNull(reloaded.getFinalDiagnosis());
    }
}
