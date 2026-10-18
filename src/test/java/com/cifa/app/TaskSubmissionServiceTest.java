package com.cifa.app;

import com.cifa.llm.FakeLLMProvider;
import com.cifa.persistence.entity.DiagnosisTaskEntity;
import com.cifa.persistence.mapper.DiagnosisTaskMapper;
import com.cifa.service.AgentDiagnosisService;
import com.cifa.service.BaselineFailureAnalyzer;
import com.cifa.task.TaskStatus;
import com.cifa.tool.JsonFlakyHistorySource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-5 / Option-D protocol verification:
 *  - accepted submissions have an observable PENDING row the instant submit returns;
 *  - rejected submissions leave ZERO rows (rejection is never a FAILED row).
 * REQUIRES DOCKER — skipped otherwise.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class TaskSubmissionServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DiagnosisTaskStore store;

    @Autowired
    private DiagnosisTaskMapper taskMapper;

    private ThreadPoolTaskExecutor pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Test
    void rejectedSubmissionLeavesZeroRows() throws Exception {
        // 1 worker + 1 queue slot, both pre-occupied: every submit must reject.
        CountDownLatch blocker = new CountDownLatch(1);
        pool = newBoundedPool(1, 1, 1);
        pool.execute(() -> awaitQuietly(blocker));
        pool.execute(() -> { });

        RepoRegistry registry = new RepoRegistry("");
        registry.register("demo-repo", Files.createTempDirectory("submission-repo"));
        TaskSubmissionService service = new TaskSubmissionService(pool, taskMapper, store,
                new DiagnosisTaskExecutor(store, new AgentDiagnosisService(),
                        new BaselineFailureAnalyzer(), JsonFlakyHistorySource.empty()),
                registry, new FakeLLMProvider(), "fake");

        TaskSubmissionService.TaskSubmissionRejected rejected = assertThrows(
                TaskSubmissionService.TaskSubmissionRejected.class,
                () -> service.submit("agent", "demo-repo", "log"));

        assertNull(taskMapper.selectById(rejected.taskId()),
                "rejection must leave zero rows (Option D compensating delete)");

        blocker.countDown();
    }

    @Test
    void acceptedSubmissionIsImmediatelyObservableAndReachesTerminalState() throws Exception {
        pool = newBoundedPool(2, 4, 8);
        RepoRegistry registry = new RepoRegistry("");
        registry.register("demo-repo", Files.createTempDirectory("submission-repo"));
        TaskSubmissionService service = new TaskSubmissionService(pool, taskMapper, store,
                new DiagnosisTaskExecutor(store, new AgentDiagnosisService(),
                        new BaselineFailureAnalyzer(), JsonFlakyHistorySource.empty()),
                registry, new FakeLLMProvider(), "fake");

        String taskId = service.submit("agent", "demo-repo", "");

        // synchronous existence confirmation: queryable the moment submit returns
        DiagnosisTaskEntity rightAfterSubmit = taskMapper.selectById(taskId);
        assertNotNull(rightAfterSubmit, "accepted task must be observable immediately");
        assertEquals("PENDING", rightAfterSubmit.getStatus());

        awaitTerminal(taskId);
        assertEquals("UNKNOWN", taskMapper.selectById(taskId).getStatus());
    }

    
    private ThreadPoolTaskExecutor newBoundedPool(int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("submission-test-");
        executor.initialize();
        return executor;
    }

    private static void awaitQuietly(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitTerminal(String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            DiagnosisTaskEntity task = taskMapper.selectById(taskId);
            if (task != null && TaskStatus.valueOf(task.getStatus()).isTerminal()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("task did not reach a terminal state in time");
    }
}
