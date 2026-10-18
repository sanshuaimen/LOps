package com.cifa.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded worker pool for diagnosis tasks. Queue is bounded and rejection is
 * ABORT (synchronous TaskRejectedException) — that exception is what makes the
 * Option-D submission protocol work: the caller learns about saturation in the
 * same request and can compensate (delete the just-inserted row, answer 429).
 * An unbounded queue or CallerRuns would silently break both properties.
 */
@Configuration
public class TaskExecutorConfig {

    @Bean(name = "diagnosisTaskExecutorPool")
    public ThreadPoolTaskExecutor diagnosisTaskExecutorPool(
            @Value("${diagnosis.executor.core-pool-size:2}") int corePoolSize,
            @Value("${diagnosis.executor.max-pool-size:4}") int maxPoolSize,
            @Value("${diagnosis.executor.queue-capacity:8}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("diagnosis-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
