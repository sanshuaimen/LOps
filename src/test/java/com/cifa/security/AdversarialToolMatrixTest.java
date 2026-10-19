package com.cifa.security;

import com.cifa.evidence.EvidenceCatalog;
import com.cifa.git.GitCommandExecutor;
import com.cifa.tool.DependencyAnalysisTool;
import com.cifa.tool.FailureEvidenceTool;
import com.cifa.tool.FlakyTestHistoryTool;
import com.cifa.tool.GitHistoryTool;
import com.cifa.tool.JsonFlakyHistorySource;
import com.cifa.tool.SourceLocationTool;
import com.cifa.tool.SourceSymbolSearchTool;
import com.cifa.tool.ToolResult;
import com.cifa.tool.ToolStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Adversarial argument matrix across the model-reachable tools. Zero tolerance:
 * hostile arguments end in a structured rejection (or an innocent NO_DATA) —
 * never an execution, a read outside the repo root, or a leaked exception.
 */
class AdversarialToolMatrixTest {

    @TempDir
    Path repo;

    // ---- git revision injection: validateRevision's allowlist must hold ----

    @Test
    void hostileGitRevisionsAreAllRejected() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        GitHistoryTool tool = new GitHistoryTool(catalog, new GitCommandExecutor(repo));
        List<String> payloads = List.of(
                "--upload-pack=x", "-oProxyCommand=socks5://evil", "--exec", "--",
                "HEAD; rm -rf /", "`id`", "a=b", "x".repeat(129), "-");

        for (String revision : payloads) {
            ToolResult result = tool.execute(Map.of(
                    "path", "A.java", "operation", "show", "revision", revision));
            assertEquals(ToolStatus.INVALID_ARGUMENT, result.status(), revision);
        }
        assertEquals(0, catalog.size(), "rejected calls must not register evidence");
    }

    @Test
    void gitOperationWhitelistHolds() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        GitHistoryTool tool = new GitHistoryTool(catalog, new GitCommandExecutor(repo));

        assertEquals(ToolStatus.INVALID_ARGUMENT,
                tool.execute(Map.of("path", "A.java", "operation", "log --upload-pack")).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT,
                tool.execute(Map.of("path", "A.java", "operation", "rebase")).status());
        assertEquals(0, catalog.size());
    }

    // ---- path escape vectors through the model-supplied `path` argument ----

    @Test
    void hostilePathsNeverReadOutsideTheRoot() {
        EvidenceCatalog locationCatalog = new EvidenceCatalog("task-1");
        SourceLocationTool location = new SourceLocationTool(locationCatalog,
                new com.cifa.security.RepositoryPathPolicy(repo));
        EvidenceCatalog gitCatalog = new EvidenceCatalog("task-2");
        GitHistoryTool git = new GitHistoryTool(gitCatalog, new GitCommandExecutor(repo));
        List<String> payloads = List.of(
                "../outside", "/etc/passwd", "a\0b");
        // harmless-failure shapes: valid relative names that simply do not exist
        // (a leading dash is a legal FILENAME, and git paths travel after `--`)
        List<String> harmless = List.of("https://evil/x", "file:///etc/passwd", "-etc/shadow");

        for (String path : payloads) {
            assertEquals(ToolStatus.INVALID_ARGUMENT,
                    location.execute(Map.of("path", path, "startLine", 1, "endLine", 2)).status(), path);
            assertEquals(ToolStatus.INVALID_ARGUMENT,
                    git.execute(Map.of("path", path, "operation", "log")).status(), path);
        }
        // the invariant is only "not OK, no evidence" — never the outside content
        for (String path : harmless) {
            assertNotEquals(ToolStatus.OK,
                    location.execute(Map.of("path", path, "startLine", 1, "endLine", 2)).status(), path);
            assertNotEquals(ToolStatus.OK,
                    git.execute(Map.of("path", path, "operation", "log")).status(), path);
        }
        assertEquals(0, locationCatalog.size());
        assertEquals(0, gitCatalog.size());
    }

    // ---- free-text arguments stay literal: no regex, no command, no path ----

    @Test
    void failureEvidenceKeywordIsALiteralNotARegex() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        FailureEvidenceTool tool = new FailureEvidenceTool(catalog, "[INFO] build ok\n");

        // .* as a regex would match every line; as a literal it matches nothing
        assertEquals(ToolStatus.NO_DATA, tool.execute(Map.of("keyword", ".*|; rm -rf ${HOME}")).status());
        assertEquals(0, catalog.size());
    }

    @Test
    void flakyTestNameIsAnExactKeyLookup() {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        FlakyTestHistoryTool tool = new FlakyTestHistoryTool(catalog, JsonFlakyHistorySource.empty());

        ToolResult result = tool.execute(Map.of("testName", "../../etc/passwd"));

        assertEquals(ToolStatus.NO_DATA, result.status());
        assertEquals(0, catalog.size());
    }

    // ---- W1 regression: walk-based tools must not follow file symlinks ----

    @Test
    void symbolSearchDoesNotFollowFileSymlinks() throws Exception {
        assumeTrue(symlinksSupported(repo));
        Path outside = Files.createTempDirectory("outside-root");
        Files.writeString(outside.resolve("Secret.java"), "class Secret { String SECRET_TOKEN = \"x\"; }");
        Files.writeString(repo.resolve("Real.java"), "class Real {}\n");
        Files.createSymbolicLink(repo.resolve("Evil.java"), outside.resolve("Secret.java"));

        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        SourceSymbolSearchTool tool = new SourceSymbolSearchTool(catalog,
                new com.cifa.security.RepositoryPathPolicy(repo));

        // the symbol exists ONLY behind the symlink: it must not be scanned
        ToolResult result = tool.execute(Map.of("symbol", "SECRET_TOKEN"));
        assertEquals(ToolStatus.NO_DATA, result.status(), result.message());

        ToolResult real = tool.execute(Map.of("symbol", "Real"));
        assertEquals(ToolStatus.OK, real.status(), "real repository files must still be scanned");
    }

    @Test
    void dependencyAnalysisDoesNotFollowPomSymlinks() throws Exception {
        assumeTrue(symlinksSupported(repo));
        Path outside = Files.createTempDirectory("outside-root");
        Files.writeString(outside.resolve("pom.xml"), "<project><artifactId>outside</artifactId></project>");
        Files.createSymbolicLink(repo.resolve("pom.xml"), outside.resolve("pom.xml"));

        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        DependencyAnalysisTool tool = new DependencyAnalysisTool(catalog,
                new com.cifa.security.RepositoryPathPolicy(repo));

        ToolResult result = tool.execute(Map.of());

        assertEquals(ToolStatus.NO_DATA, result.status(), result.message());
        assertEquals(0, catalog.size());
    }

    private static boolean symlinksSupported(Path dir) {
        try {
            Path probe = dir.resolve(".symlink-probe");
            Files.createSymbolicLink(probe, Path.of("target"));
            Files.deleteIfExists(probe);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
    }
}
