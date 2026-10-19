package com.cifa.tool;

import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FailureEvidenceToolTest {

    private static final String LOG = """
            [INFO] Building service 1.0
            [ERROR] java.lang.NullPointerException
             at com.example.UserService.find(UserService.java:9)
            [ERROR] Tests run: 1, Failures: 1
            [INFO] BUILD FAILURE
            """;

    @Test
    void matchesKeywordAndRegistersLogEvidenceWithRealRange() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        FailureEvidenceTool tool = new FailureEvidenceTool(catalog, LOG);

        ToolResult result = tool.execute(Map.of("keyword", "NullPointerException"));

        assertEquals(ToolStatus.OK, result.status(), result.message());
        assertEquals(1, result.evidenceIds().size());
        assertTrue(catalog.find(result.evidenceIds().get(0)).isPresent());
        var evidence = catalog.find(result.evidenceIds().get(0)).orElseThrow();
        assertEquals(EvidenceType.LOG, evidence.type());
        assertEquals(2, evidence.startLine());
        assertEquals(2, evidence.endLine());
        assertTrue(result.message().contains("java.lang.NullPointerException"));
    }

    @Test
    void readsExplicitLineRange() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        FailureEvidenceTool tool = new FailureEvidenceTool(catalog, LOG);

        ToolResult result = tool.execute(Map.of("startLine", 4, "endLine", 5));

        assertEquals(ToolStatus.OK, result.status());
        assertTrue(result.message().contains("4: [ERROR] Tests run: 1"));
        assertEquals(4, catalog.all().get(0).startLine());
        assertEquals(5, catalog.all().get(0).endLine());
    }

    @Test
    void reportsNoDataWhenNothingMatches() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        FailureEvidenceTool tool = new FailureEvidenceTool(catalog, LOG);

        ToolResult byKeyword = tool.execute(Map.of("keyword", "segfault"));
        ToolResult byRange = tool.execute(Map.of("startLine", 99, "endLine", 120));

        assertEquals(ToolStatus.NO_DATA, byKeyword.status());
        assertEquals(ToolStatus.NO_DATA, byRange.status());
        assertEquals(0, catalog.size());
    }

    @Test
    void rejectsInvalidRanges() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        FailureEvidenceTool tool = new FailureEvidenceTool(catalog, LOG);

        assertEquals(ToolStatus.INVALID_ARGUMENT, tool.execute(Map.of("startLine", 0)).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT, tool.execute(Map.of("startLine", 5, "endLine", 2)).status());
    }

    @Test
    void capsEvidenceContentOnHugeSingleLine() {
        // MAX_MATCH_LINES bounds the line count, not the bytes — one multi-MB line
        // matched by a broad keyword must not become an unbounded evidence entry
        String hugeLine = "[ERROR] " + "x".repeat(20_000);
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        FailureEvidenceTool tool = new FailureEvidenceTool(catalog, hugeLine);

        ToolResult result = tool.execute(Map.of("keyword", "ERROR"));

        assertEquals(ToolStatus.OK, result.status());
        assertTrue(result.message().length() <= 8100,
                "evidence must be char-capped, got " + result.message().length());
        assertTrue(result.message().endsWith("...[truncated]"));
    }
}
