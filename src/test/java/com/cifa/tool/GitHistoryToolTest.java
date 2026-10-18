package com.cifa.tool;

import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.git.GitCommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GitHistoryToolTest {

    @Test
    void logRegistersGitEvidenceWithRealCommit(@TempDir Path repo) throws Exception {
        initRepo(repo);
        Files.writeString(repo.resolve("Service.java"), "class Service {}\n");
        git(repo, "add", "Service.java");
        git(repo, "commit", "-q", "-m", "add service");
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        GitHistoryTool tool = new GitHistoryTool(catalog, new GitCommandExecutor(repo));

        ToolResult result = tool.execute(Map.of("path", "Service.java", "operation", "log"));

        assertEquals(ToolStatus.OK, result.status(), result.message());
        var evidence = catalog.find(result.evidenceIds().get(0)).orElseThrow();
        assertEquals(EvidenceType.GIT, evidence.type());
        assertFalse(evidence.commit().isBlank());
        assertTrue(evidence.commit().matches("[0-9a-f]{7,40}"));
        assertTrue(evidence.hasCommit());
    }

    @Test
    void blameAndShowAndDiffCarryCommits(@TempDir Path repo) throws Exception {
        initRepo(repo);
        Files.writeString(repo.resolve("Service.java"), "int value = 1;\n");
        git(repo, "add", "Service.java");
        git(repo, "commit", "-q", "-m", "initial");
        String revision = gitOutput(repo, "rev-parse", "HEAD").trim();
        Files.writeString(repo.resolve("Service.java"), "int value = 2;\n");
        git(repo, "commit", "-aqm", "bump value");

        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        GitHistoryTool tool = new GitHistoryTool(catalog, new GitCommandExecutor(repo));

        ToolResult blame = tool.execute(Map.of("path", "Service.java", "operation", "blame", "startLine", 1, "endLine", 1));
        ToolResult show = tool.execute(Map.of("path", "Service.java", "operation", "show", "revision", revision));
        ToolResult diff = tool.execute(Map.of("path", "Service.java", "operation", "diff", "revision", revision));

        assertEquals(ToolStatus.OK, blame.status(), blame.message());
        assertEquals(ToolStatus.OK, show.status(), show.message());
        assertEquals(ToolStatus.OK, diff.status(), diff.message());
        assertTrue(blame.message().contains("value = 2"), blame.message());
        assertTrue(show.message().contains("initial"));
        assertTrue(diff.message().contains("value = 2"));
        for (String id : List.of(blame.evidenceIds().get(0), show.evidenceIds().get(0), diff.evidenceIds().get(0))) {
            assertFalse(catalog.find(id).orElseThrow().commit().isBlank());
        }
    }

    @Test
    void rejectsBadArgumentsAndEscapes(@TempDir Path repo) throws Exception {
        initRepo(repo);
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        GitHistoryTool tool = new GitHistoryTool(catalog, new GitCommandExecutor(repo));

        assertEquals(ToolStatus.INVALID_ARGUMENT, tool.execute(Map.of()).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT, tool.execute(Map.of("path", "../outside")).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT,
                tool.execute(Map.of("path", "A.java", "operation", "rebase")).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT,
                tool.execute(Map.of("path", "A.java", "operation", "show", "revision", "--help")).status());
        assertEquals(0, catalog.size());
    }

    @Test
    void reportsGitFailureAsStructuredFailedResult(@TempDir Path repo) throws Exception {
        initRepo(repo);
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        GitHistoryTool tool = new GitHistoryTool(catalog, new GitCommandExecutor(repo));

        // Never-committed path: git exits non-zero and the tool answers with a structured failure.
        ToolResult result = tool.execute(Map.of("path", "Ghost.java", "operation", "log"));

        assertEquals(ToolStatus.FAILED, result.status());
        assertTrue(result.evidenceIds().isEmpty());
        assertEquals(0, catalog.size());
    }

    private static void initRepo(Path repo) throws Exception {
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }

    private static String gitOutput(Path repo, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
