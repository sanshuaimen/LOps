package com.cifa.app;

import com.cifa.llm.LLMProvider;
import com.cifa.llm.LLMRequest;
import com.cifa.llm.LLMResponse;
import com.cifa.persistence.entity.DiagnosisTaskEntity;
import com.cifa.persistence.mapper.DiagnosisTaskMapper;
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
 * P1-7: cancel while the model call is in flight. Semantics are cooperative:
 * the interrupt lands inside the blocking provider call, the loop refuses to
 * commit anything, and the row lands in CANCELLED without a diagnosis.
 * REQUIRES DOCKER — skipped otherwise.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class CancelChainTest {

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
            pool.getThreadPoolExecutor().shutdownNow();
        }
    }

    @Test
    void cancelDuringModelCallYieldsCancelledWithoutDiagnosis() throws Exception {
        pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(4);
        pool.setThreadNamePrefix("cancel-test-");
        pool.initialize();

        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // NOTE: a lambda here would implement analyze() (LLMProvider's single abstract
        // method) and silently never block the loop's invoke() — anonymous class on purpose.
        LLMProvider blockingProvider = new LLMProvider() {
            @Override
            public LLMResponse invoke(LLMRequest request) throws InterruptedException {
                callStarted.countDown();
                // interrupting cancel exits here immediately with InterruptedException
                release.await();
                throw new IllegalStateException("unreachable");
            }

            @Override
            public com.cifa.llm.AnalysisResult analyze(com.cifa.context.AnalysisContext context) {
                throw new UnsupportedOperationException("agent loop uses invoke()");
            }
        };

        RepoRegistry registry = new RepoRegistry("");
        registry.register("demo-repo", Files.createTempDirectory("cancel-repo"));
        DiagnosisTaskExecutor executor = new DiagnosisTaskExecutor(store,
                new com.cifa.service.AgentDiagnosisService(),
                new com.cifa.service.BaselineFailureAnalyzer(),
                JsonFlakyHistorySource.empty());
        TaskSubmissionService submissions = new TaskSubmissionService(pool, taskMapper, store,
                executor, registry, blockingProvider, "fake");

        String taskId = submissions.submit("agent", "demo-repo", "log");

        // the provider being inside invoke() proves the row is RUNNING (markRunning
        // precedes the model call), without racy status polling
        assertTrue(callStarted.await(15, java.util.concurrent.TimeUnit.SECONDS),
                "the agent loop must have entered the (blocking) model call");
        assertEquals(TaskStatus.RUNNING.name(), taskMapper.selectById(taskId).getStatus());
        assertTrue(submissions.cancel(taskId), "cancel must reach the in-flight future");

        awaitStatus(taskId, TaskStatus.CANCELLED, 15_000);
        DiagnosisTaskEntity reloaded = taskMapper.selectById(taskId);
        assertEquals("CANCELLED", reloaded.getStatus());
        assertNull(reloaded.getFinalDiagnosis(), "cancelled task must not carry a diagnosis");
        assertEquals("CANCELLED", reloaded.getStopReason());
    }

    private void awaitStatus(String taskId, TaskStatus expected, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            DiagnosisTaskEntity task = taskMapper.selectById(taskId);
            if (task != null && task.getStatus().equals(expected.name())) {
                return;
            }
            Thread.sleep(25);
        }
        fail("task " + taskId + " did not reach " + expected + " in time");
    }
}
