package com.cifa.tool;

import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.security.RepositoryPathPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SourceLocationToolTest {

    @Test
    void readsNumberedSnippetAndRegistersSourceEvidence(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("src/main/java/com/example"));
        Path file = repo.resolve("src/main/java/com/example/UserService.java");
        StringBuilder source = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            source.append("// line ").append(i).append('\n');
        }
        Files.writeString(file, source.toString());
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        SourceLocationTool tool = new SourceLocationTool(catalog, new RepositoryPathPolicy(repo));

        ToolResult result = tool.execute(Map.of("path", "src/main/java/com/example/UserService.java",
                "startLine", 9, "endLine", 11));

        assertEquals(ToolStatus.OK, result.status(), result.message());
        assertTrue(result.message().contains("9: // line 9"));
        assertTrue(result.message().contains("11: // line 11"));
        assertFalse(result.message().contains("12: "));
        var evidence = catalog.find(result.evidenceIds().get(0)).orElseThrow();
        assertEquals(EvidenceType.SOURCE, evidence.type());
        assertEquals("src/main/java/com/example/UserService.java:9-11", evidence.locator());
        assertEquals(9, evidence.startLine());
        assertEquals(11, evidence.endLine());
    }

    @Test
    void rejectsEscapingAndAbsolutePaths(@TempDir Path repo) throws Exception {
        Path outside = Files.createTempDirectory("src-outside");
        Files.writeString(outside.resolve("secret.java"), "secret");
        Files.createSymbolicLink(repo.resolve("link"), outside);
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        SourceLocationTool tool = new SourceLocationTool(catalog, new RepositoryPathPolicy(repo));

        assertEquals(ToolStatus.INVALID_ARGUMENT,
                tool.execute(Map.of("path", "../secret.java")).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT,
                tool.execute(Map.of("path", outside.resolve("secret.java").toString())).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT,
                tool.execute(Map.of("path", "link/secret.java")).status());
        assertEquals(0, catalog.size());
    }

    @Test
    void capsLinesPerReadAndRejectsBadRanges(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("A.java"), "x\n");
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        SourceLocationTool tool = new SourceLocationTool(catalog, new RepositoryPathPolicy(repo));

        assertEquals(ToolStatus.INVALID_ARGUMENT,
                tool.execute(Map.of("path", "A.java", "startLine", 1, "endLine", 500)).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT,
                tool.execute(Map.of("path", "A.java", "startLine", 5, "endLine", 2)).status());
        assertEquals(ToolStatus.FAILED,
                tool.execute(Map.of("path", "Missing.java")).status());
        assertEquals(ToolStatus.NO_DATA,
                tool.execute(Map.of("path", "A.java", "startLine", 99)).status());
    }
}
