package com.cifa.app;

import com.cifa.persistence.entity.DiagnosisTaskEntity;
import com.cifa.persistence.mapper.DiagnosisTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-8: orphans in PENDING (crash between insert and enqueue) and RUNNING
 * (crash mid-execution) both become FAILED with the restart marker.
 * REQUIRES DOCKER — skipped otherwise.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class StartupRecoveryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DiagnosisTaskMapper taskMapper;

    @Autowired
    private StartupRecoveryRunner recovery;

    @Test
    void pendingAndRunningOrphansBecomeFailedWithRestartMarker() {
        insert("orphan-pending", "PENDING");
        insert("orphan-running", "RUNNING");
        insert("finished", "SUCCEEDED");

        recovery.run(null);

        assertEquals("FAILED", taskMapper.selectById("orphan-pending").getStatus());
        assertEquals(StartupRecoveryRunner.RESTART_MESSAGE,
                taskMapper.selectById("orphan-pending").getErrorMessage());
        assertEquals("FAILED", taskMapper.selectById("orphan-running").getStatus());
        assertEquals("INTERRUPTED_BY_RESTART", taskMapper.selectById("orphan-running").getStopReason());
        // terminal rows are never touched
        assertEquals("SUCCEEDED", taskMapper.selectById("finished").getStatus());
    }

    private void insert(String id, String status) {
        DiagnosisTaskEntity task = new DiagnosisTaskEntity();
        task.setId(id);
        task.setStatus(status);
        task.setMode("agent");
        task.setRepoAlias("demo-repo");
        task.setStopReason("");
        task.setErrorMessage("");
        task.setCreatedAt(OffsetDateTime.now());
        taskMapper.insert(task);
    }
}
