package com.cifa.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Local (no-Docker) verification of the bounded pool semantics that Option D
 * depends on: synchronous rejection via AbortPolicy when the queue saturates.
 */
class TaskExecutorConfigTest {

    private ThreadPoolTaskExecutor pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Test
    void saturatedPoolRejectsSynchronouslyWithAbortPolicy() throws Exception {
        pool = new TaskExecutorConfig().diagnosisTaskExecutorPool(1, 1, 1);
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        List<String> completed = new CopyOnWriteArrayList<>();

        pool.execute(() -> {
            firstStarted.countDown();
            try {
                blocker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            completed.add("first");
        });
        assertTrue(firstStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));

        // fills the single queue slot
        pool.execute(() -> completed.add("queued"));
        // pool is busy AND queue is full -> synchronous rejection, no third task
        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> completed.add("rejected")));

        blocker.countDown();
        // graceful shutdown via the raw executor: queued tasks still run
        // (TaskExecutorConfig sets waitForTasksToComplete=false on purpose,
        // so pool.shutdown() would drop the queue — correct for app restarts).
        pool.getThreadPoolExecutor().shutdown();
        assertTrue(pool.getThreadPoolExecutor().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(List.of("first", "queued"), completed);
    }

    @Test
    void poolConfigDefaultsAreBounded() {
        pool = new TaskExecutorConfig().diagnosisTaskExecutorPool(2, 4, 8);
        assertEquals(2, pool.getCorePoolSize());
        assertEquals(4, pool.getMaxPoolSize());
        assertEquals(8, pool.getQueueCapacity());
        assertEquals(java.util.concurrent.ThreadPoolExecutor.AbortPolicy.class,
                pool.getThreadPoolExecutor().getRejectedExecutionHandler().getClass());
    }
}
