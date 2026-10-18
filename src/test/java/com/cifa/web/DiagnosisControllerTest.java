package com.cifa.web;

import com.cifa.app.RepoRegistry;
import com.cifa.app.TaskSubmissionService;
import com.cifa.persistence.entity.DiagnosisTaskEntity;
import com.cifa.persistence.entity.EvidenceEntity;
import com.cifa.persistence.mapper.DiagnosisTaskMapper;
import com.cifa.persistence.mapper.EvidenceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-mapping coverage for the three endpoints, verified locally with mocked
 * collaborators (the DB-backed happy path is covered by the Testcontainers suite).
 */
@WebMvcTest(DiagnosisController.class)
class DiagnosisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskSubmissionService submissions;

    @MockitoBean
    private RepoRegistry repoRegistry;

    @MockitoBean
    private DiagnosisTaskMapper taskMapper;

    @MockitoBean
    private EvidenceMapper evidenceMapper;

    @Test
    void createReturnsAcceptedWithTaskId() throws Exception {
        when(repoRegistry.knows("demo-repo")).thenReturn(true);
        when(submissions.submit("agent", "demo-repo", "log content")).thenReturn("task-abc");

        mockMvc.perform(post("/api/diagnoses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"agent\",\"repo\":\"demo-repo\",\"log\":\"log content\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-abc"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void createValidationFailuresMapToCorrectStatusCodes() throws Exception {
        when(repoRegistry.knows("demo-repo")).thenReturn(true);
        when(repoRegistry.knows("nope")).thenReturn(false);

        // 400: bad mode / unknown alias / null log
        mockMvc.perform(post("/api/diagnoses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"mcp\",\"repo\":\"demo-repo\",\"log\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/diagnoses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"agent\",\"repo\":\"nope\",\"log\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/diagnoses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"agent\",\"repo\":\"demo-repo\"}"))
                .andExpect(status().isBadRequest());

        // 413: oversized log
        String oversized = "x".repeat(2_000_001);
        mockMvc.perform(post("/api/diagnoses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"agent\",\"repo\":\"demo-repo\",\"log\":\"" + oversized + "\"}"))
                .andExpect(status().isPayloadTooLarge());

        Mockito.verifyNoInteractions(submissions);
    }

    @Test
    void saturatedQueueMapsToTooManyRequests() throws Exception {
        when(repoRegistry.knows("demo-repo")).thenReturn(true);
        when(submissions.submit(anyString(), anyString(), anyString()))
                .thenThrow(new TaskSubmissionService.TaskSubmissionRejected("task-x"));

        mockMvc.perform(post("/api/diagnoses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"agent\",\"repo\":\"demo-repo\",\"log\":\"x\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void statusReturnsEvidenceIdsButNeverEvidenceContent() throws Exception {
        when(taskMapper.selectById("task-1")).thenReturn(taskRow("task-1", "SUCCEEDED",
                "{\"category\":\"LOGIC_ERROR\",\"rootCause\":\"null customer\"}"));
        EvidenceEntity evidence = new EvidenceEntity();
        evidence.setTaskId("task-1");
        evidence.setEvidenceId("LOG-001");
        evidence.setContent("THIS CONTENT MUST NOT LEAK");
        when(evidenceMapper.selectList(Mockito.any())).thenReturn(List.of(evidence));

        String body = mockMvc.perform(get("/api/diagnoses/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.diagnosis.category").value("LOGIC_ERROR"))
                .andExpect(jsonPath("$.evidenceIds[0]").value("LOG-001"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("THIS CONTENT MUST NOT LEAK"), "evidence content must not be exposed");
    }

    @Test
    void unknownTaskIs404ForStatusAndCancel() throws Exception {
        when(taskMapper.selectById("ghost")).thenReturn(null);
        mockMvc.perform(get("/api/diagnoses/ghost")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/diagnoses/ghost/cancel")).andExpect(status().isNotFound());
    }

    @Test
    void cancelIsIdempotentAndReportsCurrentTerminalState() throws Exception {
        when(taskMapper.selectById("task-done")).thenReturn(taskRow("task-done", "UNKNOWN", null));

        mockMvc.perform(post("/api/diagnoses/task-done/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
        Mockito.verify(submissions).cancel("task-done");
    }

    private DiagnosisTaskEntity taskRow(String id, String status, String finalDiagnosis) throws Exception {
        DiagnosisTaskEntity task = new DiagnosisTaskEntity();
        task.setId(id);
        task.setStatus(status);
        task.setMode("agent");
        task.setRepoAlias("demo-repo");
        task.setCategory("LOGIC_ERROR");
        task.setFinalDiagnosis(finalDiagnosis);
        task.setStopReason("COMPLETED");
        task.setErrorMessage("");
        task.setCreatedAt(java.time.OffsetDateTime.now());
        task.setFinishedAt(java.time.OffsetDateTime.now());
        return task;
    }

    private void assertFalse(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertFalse(condition, message);
    }
}
