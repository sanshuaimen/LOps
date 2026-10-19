package com.cifa.app;

import com.cifa.llm.LLMProvider;
import com.cifa.persistence.entity.DiagnosisTaskEntity;
import com.cifa.persistence.mapper.DiagnosisTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * Option-D submission protocol (P1 plan, task 5): the PENDING row is inserted
 * synchronously so accepted tasks are immediately observable; when the bounded
 * pool rejects synchronously, the SAME request compensates by deleting the row
 * and signals rejection — rejection never leaves a row behind and is never
 * conflated with FAILED (which always implies execution happened).
 */
@Component
public class TaskSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(TaskSubmissionService.class);

    /** Raised when the bounded pool is saturated; the REST layer maps this to 429. */
    public static final class TaskSubmissionRejected extends RuntimeException {
        private final String taskId;

        public TaskSubmissionRejected(String taskId) {
            super("submission rejected: worker queue is full (task " + taskId + " was not accepted)");
            this.taskId = taskId;
        }

        public String taskId() {
            return taskId;
        }
    }

    private final ThreadPoolTaskExecutor pool;
    private final DiagnosisTaskMapper taskMapper;
    private final DiagnosisTaskStore store;
    private final DiagnosisTaskExecutor taskExecutor;
    private final RepoRegistry repoRegistry;
    private final LLMProvider llmProvider;
    private final String modelName;
    private final Map<String, Future<?>> inflight = new ConcurrentHashMap<>();

    public TaskSubmissionService(@Qualifier("diagnosisTaskExecutorPool") ThreadPoolTaskExecutor pool,
                                 DiagnosisTaskMapper taskMapper,
                                 DiagnosisTaskStore store,
                                 DiagnosisTaskExecutor taskExecutor,
                                 RepoRegistry repoRegistry,
                                 LLMProvider llmProvider,
                                 @Value("${diagnosis.llm.model-name:server-model}") String modelName) {
        this.pool = pool;
        this.taskMapper = taskMapper;
        this.store = store;
        this.taskExecutor = taskExecutor;
        this.repoRegistry = repoRegistry;
        this.llmProvider = llmProvider;
        this.modelName = modelName;
    }

    /**
     * @return the generated task id (the row exists and is PENDING when this returns)
     * @throws TaskSubmissionRejected when the pool is saturated; zero rows remain
     */
    public String submit(String mode, String repoAlias, String logContent) {
        String taskId = "task-" + UUID.randomUUID();
        DiagnosisTaskEntity task = new DiagnosisTaskEntity();
        task.setId(taskId);
        task.setStatus(com.cifa.task.TaskStatus.PENDING.name());
        task.setMode(mode);
        task.setRepoAlias(repoAlias);
        task.setStopReason("");
        task.setErrorMessage("");
        task.setCreatedAt(OffsetDateTime.now());
        taskMapper.insert(task); // synchronous existence confirmation

        try {
            // The worker re-selects the row: the POST thread's entity must not be
            // mutated across transactions/threads.
            Future<?> future = pool.submit(() -> executeSafely(taskId, mode, repoAlias, logContent));
            inflight.put(taskId, future);
            if (future.isDone()) {
                // the worker finished before our put: its remove was a no-op, so
                // clean up here or the entry leaks forever (fast-task race)
                inflight.remove(taskId);
            }
            return taskId;
        } catch (RejectedExecutionException rejected) {
            taskMapper.deleteById(taskId); // compensating delete: rejection leaves zero rows
            throw new TaskSubmissionRejected(taskId);
        }
    }

    private void executeSafely(String taskId, String mode, String repoAlias, String logContent) {
        try {
            DiagnosisTaskEntity task = taskMapper.selectById(taskId);
            if (task == null) {
                return; // deleted between submit and dispatch; nothing to run
            }
            Path repoRoot = repoRegistry.resolve(repoAlias);
            taskExecutor.run(task, logContent, repoRoot, llmProvider, modelName);
        } catch (RuntimeException dispatchFailure) {
            // run() is total; escapes here can only come from dispatch (resolve,
            // torn rows). A stuck RUNNING/PENDING row is worse than a FAILED row,
            // so land the failure from whatever state the row is in.
            DiagnosisTaskEntity task = taskMapper.selectById(taskId);
            if (task != null && !com.cifa.task.TaskStatus.valueOf(task.getStatus()).isTerminal()) {
                storeFallbackFailure(task, safeMessage(dispatchFailure));
            }
        } finally {
            // must NOT run before the submit-side put; see the isDone check above
            inflight.remove(taskId);
        }
    }

    private void storeFallbackFailure(DiagnosisTaskEntity task, String message) {
        try {
            store.failFromCurrentStatus(task, "EXECUTION_ERROR", message);
        } catch (RuntimeException secondary) {
            // DB itself is unhappy; nothing further can be done on this thread.
            // This used to be silent — the one place a row could get stuck in a
            // non-terminal state with zero evidence left behind. Never swallow it.
            log.warn("task {}: fallback FAILED persist failed too; row may be stuck non-terminal",
                    task.getId(), secondary);
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    /** Cooperative cancel: interrupts the worker if still running. Returns false when unknown/finished. */
    public boolean cancel(String taskId) {
        Future<?> future = inflight.get(taskId);
        return future != null && future.cancel(true);
    }
}
