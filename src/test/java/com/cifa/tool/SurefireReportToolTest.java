package com.cifa.tool;

import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SurefireReportToolTest {

    private static final String SUREFIRE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.UserServiceTest" tests="2" failures="1" errors="0" skipped="0">
              <testcase name="findsUser" classname="com.example.UserServiceTest" time="0.01"/>
              <testcase name="rejectsNull" classname="com.example.UserServiceTest" time="0.02">
                <failure message="expected: &lt;User&gt; but was: &lt;null&gt;" type="java.lang.AssertionError">java.lang.AssertionError: expected: &lt;User&gt; but was: &lt;null&gt;
                \tat com.example.UserService.reject(UserService.java:42)
                \tat com.example.UserServiceTest.rejectsNull(UserServiceTest.java:21)
                </failure>
              </testcase>
            </testsuite>
            """;

    @Test
    void parsesFailingTestsIntoTestReportEvidence(@TempDir Path repo) throws Exception {
        Path reports = Files.createDirectories(repo.resolve("target/surefire-reports"));
        Files.writeString(reports.resolve("TEST-com.example.UserServiceTest.xml"), SUREFIRE_XML);
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        SurefireReportTool tool = new SurefireReportTool(catalog, repo);

        ToolResult result = tool.execute(Map.of());

        assertEquals(ToolStatus.OK, result.status(), result.message());
        assertEquals(1, result.evidenceIds().size());
        var evidence = catalog.find(result.evidenceIds().get(0)).orElseThrow();
        assertEquals(EvidenceType.TEST_REPORT, evidence.type());
        assertTrue(result.message().contains("UserServiceTest.rejectsNull"), result.message());
        assertTrue(result.message().contains("UserService.java:42"));
    }

    @Test
    void reportsNoDataWithoutSurefireXml(@TempDir Path repo) {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        SurefireReportTool tool = new SurefireReportTool(catalog, repo);

        ToolResult result = tool.execute(Map.of());

        assertEquals(ToolStatus.NO_DATA, result.status());
        assertEquals(0, catalog.size());
    }
}
