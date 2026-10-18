package com.cifa.web;

import com.cifa.persistence.entity.DiagnosisTaskEntity;
import com.cifa.persistence.mapper.DiagnosisTaskMapper;
import com.cifa.task.TaskStatus;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-10: the same fixture through the CLI entry (com.cifa.cli.Main in-process,
 * agent mode, recorded fake script) and through the REST API must agree on
 * category / rootCause / evidenceIds / confidence / stopReason / unknown.
 * Field-level equivalence — timestamps and trace ordering deliberately not
 * compared. REQUIRES DOCKER — skipped otherwise.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        // REST side replays the same recorded script the CLI side uses
        "diagnosis.llm.provider=fake",
        "diagnosis.llm.fake-script=fixtures/eval/npe-log/agent-script.json"
})
@AutoConfigureMockMvc
class CliRestEquivalenceTest {

    private static final Path FIXTURES = Path.of("fixtures/eval/npe-log");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DiagnosisTaskMapper taskMapper;

    @Autowired
    private com.cifa.app.RepoRegistry repoRegistry;

    @Test
    void cliAndRestProduceEquivalentAgentDiagnoses() throws Exception {
        // CLI side: the real entry point, recorded fake script, real fixture files.
        String cliStdout = captureStdout(() -> {
            try {
                com.cifa.cli.Main.main(new String[]{
                "--mode", "agent",
                "--provider", "fake",
                "--fake-script", FIXTURES.resolve("agent-script.json").toString(),
                "--log", FIXTURES.resolve("build.log").toString(),
                "--repo", FIXTURES.resolve("repo").toString(),
                "--task-id", "equiv-cli",
                "--trace", "target/equiv-cli-trace.jsonl"
                });
            } catch (Exception e) {
                throw new IllegalStateException("CLI run failed", e);
            }
        });
        String cliJson = cliStdout.substring(cliStdout.indexOf('{'), cliStdout.lastIndexOf('}') + 1);
        JSONObject cliDiagnosis = new JSONObject(cliJson);
        // REST side: same fixture content and script via the API.
        repoRegistry.register("npe-log", FIXTURES.resolve("repo").toAbsolutePath().normalize());
        String logContent = Files.readString(FIXTURES.resolve("build.log"));
        mockMvc.perform(post("/api/diagnoses")
                        .contentType("application/json")
                        .content(new JSONObject()
                                .put("mode", "agent")
                                .put("repo", "npe-log")
                                .put("log", logContent).toString()))
                .andExpect(status().isAccepted());

        String restJson = awaitTerminalAndFetch("task-");
        JSONObject restDiagnosis = new JSONObject(restJson);

        for (String field : new String[]{"category", "rootCause", "evidenceIds", "confidence",
                "stopReason", "unknown"}) {
            // compare as plain values: JSONArrays may be different List implementations
            // on the two paths (parse-direct vs Jackson-roundtrip), so JSONArray.equals
            // can trip on identity even when the printed contents are identical.
            assertEquals(normalize(cliDiagnosis.get(field)), normalize(restDiagnosis.get(field)),
                    () -> "field " + field + " diverges between CLI and REST");
        }
    }

    /** Flatten JSONArrays to plain Lists so equality is elementwise, not type-identity. */
    private static Object normalize(Object value) {
        if (value instanceof org.json.JSONArray array) {
            return array.toList();
        }
        return value;
    }

    private String awaitTerminalAndFetch(String idPrefix) throws Exception {
        String taskId;
        long deadline = System.currentTimeMillis() + 20_000;
        // poll the DB for the single task created via REST
        String foundId = null;
        while (System.currentTimeMillis() < deadline) {
            for (DiagnosisTaskEntity task : taskMapper.selectList(null)) {
                if (task.getId().startsWith(idPrefix)) {
                    foundId = task.getId();
                    if (TaskStatus.valueOf(task.getStatus()).isTerminal()) {
                        String body = mockMvc.perform(get("/api/diagnoses/" + foundId))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.evidenceIds").isArray())
                                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                        return new JSONObject(body).getJSONObject("diagnosis").toString();
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no terminal REST task appeared in time");
    }

    private static String captureStdout(Runnable runnable) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            runnable.run();
        } finally {
            System.out.flush();
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
