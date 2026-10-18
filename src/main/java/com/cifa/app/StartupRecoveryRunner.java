package com.cifa.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cifa.persistence.entity.DiagnosisTaskEntity;
import com.cifa.persistence.mapper.DiagnosisTaskMapper;
import com.cifa.task.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P1 plan (first-version policy): after a restart no task may stay PENDING or
 * RUNNING forever. At startup the pool is empty, so any such row is an orphan
 * from a crashed process — mark it FAILED ("interrupted by restart"). Recovery
 * of the underlying investigation is explicitly out of scope for P1.
 */
@Component
@ConditionalOnProperty(name = "diagnosis.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class StartupRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecoveryRunner.class);
    static final String RESTART_MESSAGE = "interrupted by restart";

    private final DiagnosisTaskMapper taskMapper;
    private final DiagnosisTaskStore store;

    public StartupRecoveryRunner(DiagnosisTaskMapper taskMapper, DiagnosisTaskStore store) {
        this.taskMapper = taskMapper;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<DiagnosisTaskEntity> orphans = taskMapper.selectList(
                new LambdaQueryWrapper<DiagnosisTaskEntity>().in(DiagnosisTaskEntity::getStatus,
                        TaskStatus.PENDING.name(), TaskStatus.RUNNING.name()));
        for (DiagnosisTaskEntity orphan : orphans) {
            // capture BEFORE the store mutates the entity — the whole point of
            // this log line is telling "crashed before dispatch" from "crashed
            // mid-execution" during incident review.
            String originalStatus = orphan.getStatus();
            store.failFromCurrentStatus(orphan, "INTERRUPTED_BY_RESTART", RESTART_MESSAGE);
            log.warn("task {} was {} when the process died; marked FAILED", orphan.getId(), originalStatus);
        }
        if (!orphans.isEmpty()) {
            log.warn("startup recovery marked {} orphaned task(s) as FAILED", orphans.size());
        }
    }
}
