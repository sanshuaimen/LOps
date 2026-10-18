package com.cifa.tool;

import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlakyTestHistoryToolTest {

    @Test
    void detectsRerunRecoveryAsFlakyEvidence() {
        FlakyHistorySource source = test -> {
            if (!test.equals("com.example.UserServiceTest.findsUser")) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new FlakyRunHistory(test, List.of(
                    new FlakyRun("b-1", true, false, "abc1234"),
                    new FlakyRun("b-2", false, true, ""),
                    new FlakyRun("b-3", true, false, "abc9999"))));
        };
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        FlakyTestHistoryTool tool = new FlakyTestHistoryTool(catalog, source);

        ToolResult result = tool.execute(Map.of("testName", "com.example.UserServiceTest.findsUser"));

        assertEquals(ToolStatus.OK, result.status(), result.message());
        assertTrue(result.message().contains("verdict: FLAKY_EVIDENCE"), result.message());
        assertTrue(result.message().contains("rerun recoveries: 1"), result.message());
        var evidence = catalog.find(result.evidenceIds().get(0)).orElseThrow();
        assertEquals(EvidenceType.FLAKY_HISTORY, evidence.type());
        assertEquals("flaky-history:com.example.UserServiceTest.findsUser", evidence.locator());
    }

    @Test
    void relatedCommitFailuresAreNotFlakiness() {
        FlakyRunHistory history = new FlakyRunHistory("T.m", List.of(
                new FlakyRun("b-1", true, false, "abc1234"),
                new FlakyRun("b-2", false, false, "def5678")));

        assertFalse(history.flakyEvidence());
        assertEquals(1, history.failCount());
        assertEquals(0, history.unrelatedFailureCount());
    }

    @Test
    void answersNoDataWithoutSourceOrUnknownTest() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        FlakyTestHistoryTool emptyTool = new FlakyTestHistoryTool(catalog, FlakyHistorySource.empty());
        assertEquals(ToolStatus.NO_DATA, emptyTool.execute(Map.of("testName", "A.B")).status());

        FlakyTestHistoryTool sparseTool = new FlakyTestHistoryTool(catalog, JsonFlakyHistorySource.empty());
        assertEquals(ToolStatus.NO_DATA, sparseTool.execute(Map.of("testName", "A.B")).status());
        assertEquals(0, catalog.size());
    }

    @Test
    void rejectsMissingTestName() {
        FlakyTestHistoryTool tool = new FlakyTestHistoryTool(new EvidenceCatalog("task-1"), JsonFlakyHistorySource.empty());
        assertEquals(ToolStatus.INVALID_ARGUMENT, tool.execute(Map.of()).status());
    }

    @Test
    void loadsRunsFromJsonFixture(@TempDir Path dir) throws Exception {
        Path historyFile = dir.resolve("flaky-history.json");
        Files.writeString(historyFile, new JSONObject()
                .put("com.example.UserServiceTest.findsUser", new org.json.JSONArray()
                        .put(new JSONObject().put("build", "b-1").put("passed", true))
                        .put(new JSONObject().put("build", "b-2").put("passed", false)
                                .put("rerunPassed", true).put("relatedCommit", "")))
                .toString());

        JsonFlakyHistorySource source = new JsonFlakyHistorySource(historyFile);
        FlakyRunHistory history = source.load("com.example.UserServiceTest.findsUser").orElseThrow();

        assertEquals(2, history.runs().size());
        assertTrue(history.flakyEvidence());

        assertTrue(source.load("com.example.OtherTest.other").isEmpty());
    }
}
