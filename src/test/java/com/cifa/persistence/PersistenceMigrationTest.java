package com.cifa.persistence;

import com.cifa.persistence.entity.DiagnosisTaskEntity;
import com.cifa.persistence.entity.EvidenceEntity;
import com.cifa.persistence.entity.FailureCaseEntity;
import com.cifa.persistence.entity.InvestigationStepEntity;
import com.cifa.persistence.mapper.DiagnosisTaskMapper;
import com.cifa.persistence.mapper.EvidenceMapper;
import com.cifa.persistence.mapper.FailureCaseMapper;
import com.cifa.persistence.mapper.InvestigationStepMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-3 verification: Flyway V1 creates the four core tables and the mappers
 * round-trip through them (incl. the JSONB evidence_ids column and the status
 * CHECK constraint).
 *
 * REQUIRES DOCKER. On machines without Docker access this test is skipped and
 * P1-3's "migration runs" criterion stays unverified until a Docker-capable
 * environment runs the suite.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PersistenceMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DiagnosisTaskMapper taskMapper;

    @Autowired
    private InvestigationStepMapper stepMapper;

    @Autowired
    private EvidenceMapper evidenceMapper;

    @Autowired
    private FailureCaseMapper failureCaseMapper;

    @Test
    void migratesAndRoundTripsAllFourTables() {
        DiagnosisTaskEntity task = new DiagnosisTaskEntity();
        task.setId("mig-task-1");
        task.setStatus("RUNNING");
        task.setMode("agent");
        task.setRepoAlias("npe-log");
        task.setStopReason("");
        task.setErrorMessage("");
        task.setCreatedAt(OffsetDateTime.now());
        taskMapper.insert(task);

        InvestigationStepEntity step = new InvestigationStepEntity();
        step.setTaskId("mig-task-1");
        step.setSequence(1);
        step.setRound(1);
        step.setEventType("tool_call");
        step.setModel("fake");
        step.setToolName("failure_evidence");
        step.setEvidenceIds(List.of("LOG-001", "LOG-002"));
        step.setTokenCount(42);
        step.setElapsedMillis(7L);
        step.setStatus("ok");
        stepMapper.insert(step);

        InvestigationStepEntity reloaded = stepMapper.selectById(step.getId());
        assertEquals(List.of("LOG-001", "LOG-002"), reloaded.getEvidenceIds(), "JSONB round trip");

        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setTaskId("mig-task-1");
        evidence.setEvidenceId("LOG-001");
        evidence.setType("LOG");
        evidence.setContent("1: error line\n");
        evidence.setContentHash("abc123");
        evidence.setToolName("preprocessing");
        evidence.setStartLine(1);
        evidence.setEndLine(1);
        evidence.setCommitId("");
        evidenceMapper.insert(evidence);

        FailureCaseEntity failureCase = new FailureCaseEntity();
        failureCase.setSourceTaskId("mig-task-1");
        failureCase.setFailureFingerprint("npe:user-service");
        failureCase.setCategory("LOGIC_ERROR");
        failureCase.setConfirmedDiagnosis("null customer dereference");
        failureCase.setConfirmedBy("reviewer-a");
        failureCase.setConfirmedAt(OffsetDateTime.now());
        failureCaseMapper.insert(failureCase);
        assertTrue(failureCaseMapper.selectById(failureCase.getId()).getConfirmedAt() != null);
    }

    @Test
    void statusCheckConstraintRejectsUnknownStates() {
        DiagnosisTaskEntity task = new DiagnosisTaskEntity();
        task.setId("mig-task-bad");
        task.setStatus("WEIRD");
        task.setMode("agent");
        task.setRepoAlias("npe-log");
        assertThrows(Exception.class, () -> taskMapper.insert(task),
                "status CHECK must reject states outside the state machine");
    }
}
