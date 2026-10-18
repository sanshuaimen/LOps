package com.cifa.service;

import com.cifa.agent.AgentStopReason;
import com.cifa.context.ContextBudget;
import com.cifa.llm.FakeLLMProvider;
import com.cifa.tool.JsonFlakyHistorySource;
import com.cifa.trace.InMemoryTraceRecorder;
import com.cifa.trace.JsonlTraceRecorder;
import com.cifa.trace.TraceEvent;
import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: deterministic preprocessing + controlled tool registry +
 * agent loop, driven by a recorded fake script, producing a diagnosis with
 * real evidence ids and a machine-readable trace.
 */
class AgentDiagnosisServiceE2ETest {

    private static final String BUILD_LOG = """
            [INFO] Scanning for projects...
            [INFO] Building demo 1.0
            [INFO] --- maven-surefire-plugin:3.2.5:test ---
            [ERROR] Tests run: 2, Failures: 1, Errors: 1
            [ERROR] UserServiceTest.findsUser  Time elapsed: 0.01 s  <<< FAILURE!
            java.lang.NullPointerException
            	at com.example.UserService.find(UserService.java:9)
            	at com.example.UserServiceTest.findsUser(UserServiceTest.java:12)
            [INFO] BUILD FAILURE
            """;

    @TempDir
    Path workspace;

    private Path repo;
    private Path logFile;

    private void setUpFixture() throws Exception {
        repo = Files.createDirectories(workspace.resolve("repo/src/main/java/com/example"));
        Path service = repo.resolve("UserService.java");
        Files.writeString(service, """
                package com.example;

                import java.util.Optional;

                public class UserService {
                    private final UserRepository users;

                    public UserService(UserRepository users) {
                        this.users = users;
                    }

                    public String find(String id) {
                        return users.byId(id).name();
                    }
                }
                """);
        Path test = Files.createDirectories(workspace.resolve("repo/src/test/java/com/example"));
        Files.writeString(test.resolve("UserServiceTest.java"), """
                package com.example;

                public class UserServiceTest {
                    public void findsUser() {
                        new UserService(null).find("u1");
                    }
                }
                """);
        logFile = workspace.resolve("build.log");
        Files.writeString(logFile, BUILD_LOG);
    }

    @Test
    void runsRecordedInvestigationAndProducesEvidenceBackedDiagnosisWithTrace() throws Exception {
        setUpFixture();

        Path script = workspace.resolve("recorded-script.json");
        Files.writeString(script, new JSONArray()
                .put(script("tool_call", new org.json.JSONObject()
                        .put("tool", "failure_evidence")
                        .put("arguments", new org.json.JSONObject().put("keyword", "NullPointerException"))
                        .put("hypothesis", "the log holds a direct NPE with a project frame")))
                .put(script("tool_call", new org.json.JSONObject()
                        .put("tool", "source_location")
                        .put("arguments", new org.json.JSONObject()
                                .put("path", "src/main/java/com/example/UserService.java")
                                .put("startLine", 5).put("endLine", 15))
                        .put("hypothesis", "line 9 dereferences a null lookup result")))
                .put(script("submit", new org.json.JSONObject()
                        .put("category", "LOGIC_ERROR")
                        .put("rootCause", "UserService.find dereferences users.byId(id) which returns null")
                        .put("evidenceIds", new JSONArray().put("LOG-001").put("SRC-001"))
                        .put("confidence", 78)
                        .put("suggestedAction", "null-check the Optional lookup or return Optional")))
                .toString());

        Path traceFile = workspace.resolve("trace/agent.jsonl");
        try (JsonlTraceRecorder traceRecorder = new JsonlTraceRecorder(traceFile)) {
            var output = new AgentDiagnosisService().investigate(
                    new DiagnosisRequest(logFile, workspace.resolve("repo"), ContextBudget.DEFAULT),
                    FakeLLMProvider.recorded(script), "fake",
                    com.cifa.agent.InvestigationBudget.defaults(), "e2e-1",
                    traceRecorder, JsonFlakyHistorySource.empty());

            assertEquals(AgentStopReason.COMPLETED, output.diagnosis().stopReason());
            assertFalse(output.diagnosis().unknown());
            assertEquals("LOGIC_ERROR", output.diagnosis().category());
            assertEquals(List.of("LOG-001", "SRC-001"), output.diagnosis().evidenceIds());
            // Evidence ids must resolve in the catalog: LOG-001 is preprocessing, SRC-001 came from the tool.
            assertTrue(output.catalog().find("LOG-001").isPresent());
            assertTrue(output.catalog().find("SRC-001").isPresent());
            assertEquals("failure_evidence", output.catalog().find("LOG-002").orElseThrow().toolName());
            assertEquals("source_location", output.catalog().find("SRC-001").orElseThrow().toolName());
        }

        // Trace is machine-readable JSONL with per-call hypothesis and evidence ids.
        List<String> lines = Files.readAllLines(traceFile);
        assertTrue(lines.size() >= 5, "expected >= 5 trace events, got " + lines.size());
        List<org.json.JSONObject> events = lines.stream()
                .map(org.json.JSONObject::new)
                .toList();
        assertTrue(events.stream().anyMatch(event -> "tool_call".equals(event.optString("eventType"))
                && "failure_evidence".equals(event.optString("toolName"))
                && !event.optString("hypothesis").isEmpty()));
        assertTrue(events.stream().anyMatch(event -> "conclusion".equals(event.optString("eventType"))));
        assertTrue(events.stream().anyMatch(event ->
                "tool_call".equals(event.optString("eventType"))
                        && event.optJSONArray("evidenceIds").length() > 0));
    }

    @Test
    void inMemoryVariantExposesEventsForEvalHarnesses() throws Exception {
        setUpFixture();
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();

        var output = new AgentDiagnosisService().investigate(
                new DiagnosisRequest(logFile, workspace.resolve("repo"), ContextBudget.DEFAULT),
                new FakeLLMProvider(), "fake",
                com.cifa.agent.InvestigationBudget.defaults(), "e2e-2",
                traceRecorder, JsonFlakyHistorySource.empty());

        // Unscripted fake answers unknown("No scripted response available") — honest dead end.
        assertEquals(AgentStopReason.INSUFFICIENT_EVIDENCE, output.diagnosis().stopReason());
        assertTrue(output.diagnosis().unknown());
        assertTrue(traceRecorder.events().size() >= 2);
        assertTrue(traceRecorder.events().stream()
                .anyMatch(event -> event.eventType().equals("model_decision")));
    }

    @Test
    void emptyLogDoesNotCrashRegistrationAndYieldsStructuredUnknown() throws Exception {
        setUpFixture();
        Path emptyLog = workspace.resolve("empty.log");
        Files.writeString(emptyLog, "");
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();

        var output = new AgentDiagnosisService().investigate(
                new DiagnosisRequest(emptyLog, workspace.resolve("repo"), ContextBudget.DEFAULT),
                new FakeLLMProvider(), "fake",
                com.cifa.agent.InvestigationBudget.defaults(), "e2e-empty-log",
                traceRecorder, JsonFlakyHistorySource.empty());

        // Preexisting crash: blank excerpt made EvidenceCatalog.register throw.
        assertTrue(output.catalog().find("LOG-001").isPresent());
        assertEquals("normalized_log:empty", output.catalog().find("LOG-001").orElseThrow().locator());
        assertEquals(AgentStopReason.INSUFFICIENT_EVIDENCE, output.diagnosis().stopReason());
    }

    private static org.json.JSONObject script(String type, org.json.JSONObject body) {
        return new org.json.JSONObject(body.toMap()).put("type", type);
    }
}
