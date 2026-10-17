package com.cifa.correlation;

import com.cifa.parsing.StackFrame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GitCorrelatorTest {

    @Test
    void firstProjectFrameSkipsThirdPartyPrefixes() {
        List<StackFrame> frames = List.of(
                new StackFrame("org.junit.platform.commons.util.ReflectionUtils", "invokeMethod", "ReflectionUtils.java", 728),
                new StackFrame("com.example.UserService", "calculateDiscount", "UserService.java", 9)
        );
        StackFrame result = GitCorrelator.firstProjectFrame(frames);
        assertEquals("com.example.UserService", result.className());
    }

    @Test
    void firstProjectFrameReturnsNullWhenAllThirdParty() {
        List<StackFrame> frames = List.of(
                new StackFrame("java.base.Foo", "bar", "Foo.java", 1)
        );
        assertNull(GitCorrelator.firstProjectFrame(frames));
    }

    @Test
    void resolveFilePathUsesFlatMavenLayout() {
        StackFrame frame = new StackFrame("com.example.UserService", "calculateDiscount", "UserService.java", 9);
        var path = GitCorrelator.resolveFilePath(java.nio.file.Path.of("/repo"), frame);
        assertEquals("/repo/src/main/java/com/example/UserService.java", path.toString());
    }

    @Test
    void parsesGitLogOutputIntoCommitFields() {
        String sample = """
                commit abc123def456
                Author: Jane Dev <jane@example.com>
                Date:   Mon Jul 20 10:00:00 2026 +0000

                    Apply bulk-import multiplier to discount calculation

                diff --git a/src/main/java/com/example/UserService.java b/src/main/java/com/example/UserService.java
                index 1111111..2222222 100644
                --- a/src/main/java/com/example/UserService.java
                +++ b/src/main/java/com/example/UserService.java
                @@ -6,3 +6,5 @@ public class UserService {
                         double base = 100.0;
                -        double rate = customer.getType().getDiscountRate();
                +        double rate = customer.getType().getDiscountRate();
                +        return base * rate * bulkImportMultiplier(customer);
                """;

        CorrelatedCommit commit = GitCorrelator.parseGitLogOutput(sample);

        assertEquals("abc123def456", commit.hash());
        assertEquals("Jane Dev <jane@example.com>", commit.author());
        assertTrue(commit.date().contains("2026"));
        assertTrue(commit.diffHunk().startsWith("@@"));
        assertTrue(commit.diffHunk().contains("bulkImportMultiplier"));
    }

    @Test
    void parsesBlankOutputAsNull() {
        assertNull(GitCorrelator.parseGitLogOutput(""));
    }

    @Test
    void resolveFilePathAcrossModulesFindsFileInNonDefaultModule(@org.junit.jupiter.api.io.TempDir java.nio.file.Path repoRoot) throws Exception {
        java.nio.file.Path pom = repoRoot.resolve("pom.xml");
        java.nio.file.Files.writeString(pom, """
                <project>
                  <modules>
                    <module>module-a</module>
                    <module>module-b</module>
                  </modules>
                </project>
                """);
        java.nio.file.Path classFile = repoRoot.resolve("module-b/src/main/java/com/example/b/PricingService.java");
        java.nio.file.Files.createDirectories(classFile.getParent());
        java.nio.file.Files.writeString(classFile, "package com.example.b;\npublic class PricingService {}\n");

        StackFrame frame = new StackFrame("com.example.b.PricingService", "applyDiscount", "PricingService.java", 6);
        var result = GitCorrelator.resolveFilePathAcrossModules(repoRoot, frame);

        assertTrue(result.isPresent());
        assertEquals(classFile, result.get());
    }

    @Test
    void resolveFilePathAcrossModulesFallsBackToRepoWideSearch(@org.junit.jupiter.api.io.TempDir java.nio.file.Path repoRoot) throws Exception {
        java.nio.file.Path classFile = repoRoot.resolve("some/nonstandard/layout/PricingService.java");
        java.nio.file.Files.createDirectories(classFile.getParent());
        java.nio.file.Files.writeString(classFile, "class PricingService {}\n");

        StackFrame frame = new StackFrame("com.example.b.PricingService", "applyDiscount", "PricingService.java", 6);
        var result = GitCorrelator.resolveFilePathAcrossModules(repoRoot, frame);

        assertTrue(result.isPresent());
        assertEquals(classFile, result.get());
    }

    @Test
    void resolveFilePathAcrossModulesReturnsEmptyWhenNotFoundAnywhere(@org.junit.jupiter.api.io.TempDir java.nio.file.Path repoRoot) {
        StackFrame frame = new StackFrame("com.example.Missing", "method", "Missing.java", 1);
        var result = GitCorrelator.resolveFilePathAcrossModules(repoRoot, frame);

        assertTrue(result.isEmpty());
    }
}
