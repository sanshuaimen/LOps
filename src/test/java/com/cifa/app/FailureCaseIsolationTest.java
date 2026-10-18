package com.cifa.app;

import com.cifa.llm.FakeLLMProvider;
import com.cifa.persistence.entity.DiagnosisTaskEntity;
import com.cifa.persistence.mapper.DiagnosisTaskMapper;
import com.cifa.persistence.mapper.FailureCaseMapper;
import com.cifa.service.AgentDiagnosisService;
import com.cifa.service.BaselineFailureAnalyzer;
import com.cifa.task.TaskStatus;
import com.cifa.tool.JsonFlakyHistorySource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-9: failure_case is write-isolated from the diagnosis flow. Full agent and
 * baseline runs (success, unknown, failure) must leave it EMPTY; only the
 * manual promotion service may write there.
 * REQUIRES DOCKER — skipped otherwise.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class FailureCaseIsolationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DiagnosisTaskStore store;

    @Autowired
    private DiagnosisTaskMapper taskMapper;

    @Autowired
    private FailureCaseMapper failureCaseMapper;

    @Autowired
    private FailureCasePromotionService promotion;

    @Test
    void fullDiagnosisFlowsNeverWriteFailureCases() throws Exception {
        DiagnosisTaskExecutor executor = new DiagnosisTaskExecutor(store,
                new AgentDiagnosisService(), new BaselineFailureAnalyzer(),
                JsonFlakyHistorySource.empty());
        Path repo = Files.createTempDirectory("isolation-repo");

        // agent success
        runAndAssertZeroCases(executor, repo, "iso-1", "agent", new FakeLLMProvider(
                com.cifa.llm.AgentDecision.submit("LOGIC_ERROR", "root cause", java.util.List.of("LOG-001"), 70, "fix")));
        // agent unknown
        runAndAssertZeroCases(executor, repo, "iso-2", "agent", new FakeLLMProvider());
        // agent model failure
        FakeLLMProvider failing = new FakeLLMProvider();
        failing.enqueueFailure(new java.io.IOException("down"));
        failing.enqueueFailure(new java.io.IOException("down"));
        failing.enqueueFailure(new java.io.IOException("down"));
        runAndAssertZeroCases(executor, repo, "iso-3", "agent", failing);
        // baseline
        runAndAssertZeroCases(executor, repo, "iso-4", "baseline", new FakeLLMProvider());

        // the manual path works and is the only writer
        long promotedId = promotion.promote("iso-1", "fp:npe:iso-1", "LOGIC_ERROR",
                "confirmed root cause", "fix it", "reviewer-a", "");
        assertTrue(promotedId > 0);
        assertEquals(1, failureCaseMapper.selectCount(null));
    }

    private void runAndAssertZeroCases(DiagnosisTaskExecutor executor, Path repo, String id, String mode,
                                       FakeLLMProvider provider) throws Exception {
        DiagnosisTaskEntity task = new DiagnosisTaskEntity();
        task.setId(id);
        task.setStatus(TaskStatus.PENDING.name());
        task.setMode(mode);
        task.setRepoAlias("demo-repo");
        task.setStopReason("");
        task.setErrorMessage("");
        taskMapper.insert(task);

        executor.run(task, "1: [ERROR] boom\n", repo, provider, "fake");

        assertTrue(TaskStatus.valueOf(taskMapper.selectById(id).getStatus()).isTerminal());
        assertEquals(0, failureCaseMapper.selectCount(null),
                () -> "flow " + id + " wrote to failure_case");
    }
}
