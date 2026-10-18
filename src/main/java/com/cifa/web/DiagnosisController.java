package com.cifa.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cifa.app.RepoRegistry;
import com.cifa.app.SubmissionValidator;
import com.cifa.app.TaskSubmissionService;
import com.cifa.persistence.entity.DiagnosisTaskEntity;
import com.cifa.persistence.entity.EvidenceEntity;
import com.cifa.persistence.mapper.DiagnosisTaskMapper;
import com.cifa.persistence.mapper.EvidenceMapper;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * P1 REST surface (three endpoints, no more). Assumptions live in the P1 plan:
 * no auth, repo aliases are server-configured, log content travels in the body,
 * evidence CONTENT is never exposed — ids only.
 */
@RestController
@RequestMapping("/api/diagnoses")
public class DiagnosisController {

    public record CreateDiagnosisRequest(String mode, String repo, String log) {
    }

    private final TaskSubmissionService submissions;
    private final RepoRegistry repoRegistry;
    private final DiagnosisTaskMapper taskMapper;
    private final EvidenceMapper evidenceMapper;

    public DiagnosisController(TaskSubmissionService submissions, RepoRegistry repoRegistry,
                               DiagnosisTaskMapper taskMapper, EvidenceMapper evidenceMapper) {
        this.submissions = submissions;
        this.repoRegistry = repoRegistry;
        this.taskMapper = taskMapper;
        this.evidenceMapper = evidenceMapper;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateDiagnosisRequest request) {
        try {
            SubmissionValidator.validate(request.mode(), request.repo(), request.log(), repoRegistry);
        } catch (SubmissionValidator.LogTooLargeException e) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            String taskId = submissions.submit(request.mode(), request.repo(), request.log());
            return ResponseEntity.accepted()
                    .body(Map.of("taskId", taskId, "status", "PENDING"));
        } catch (TaskSubmissionService.TaskSubmissionRejected rejected) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "worker queue is full; retry later"));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String id) {
        DiagnosisTaskEntity task = taskMapper.selectById(id);
        if (task == null) {
            return notFound();
        }
        return ResponseEntity.ok(taskView(task));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String id) {
        DiagnosisTaskEntity task = taskMapper.selectById(id);
        if (task == null) {
            return notFound();
        }
        // Cooperative + idempotent: interrupting a running worker flips it to
        // CANCELLED; finished tasks simply report their terminal state (200).
        submissions.cancel(id);
        return ResponseEntity.ok(taskView(taskMapper.selectById(id)));
    }

    private Map<String, Object> taskView(DiagnosisTaskEntity task) {
        List<String> evidenceIds = evidenceMapper.selectList(
                        new LambdaQueryWrapper<EvidenceEntity>()
                                .eq(EvidenceEntity::getTaskId, task.getId())
                                .orderByAsc(EvidenceEntity::getId))
                .stream().map(EvidenceEntity::getEvidenceId).toList();
        Object diagnosis = task.getFinalDiagnosis() == null || task.getFinalDiagnosis().isBlank()
                ? null
                : new JSONObject(task.getFinalDiagnosis()).toMap();
        // LinkedHashMap: Map.of*/Map.entry reject null values, and diagnosis is
        // legitimately null for MODEL_FAILURE / in-flight tasks.
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("taskId", task.getId());
        view.put("status", task.getStatus());
        view.put("mode", task.getMode());
        view.put("repo", task.getRepoAlias());
        view.put("category", task.getCategory() == null ? "" : task.getCategory());
        view.put("stopReason", task.getStopReason());
        view.put("error", task.getErrorMessage());
        view.put("createdAt", String.valueOf(task.getCreatedAt()));
        view.put("finishedAt", task.getFinishedAt() == null ? "" : String.valueOf(task.getFinishedAt()));
        view.put("diagnosis", diagnosis);
        view.put("evidenceIds", evidenceIds);
        return view;
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no such task"));
    }
}
