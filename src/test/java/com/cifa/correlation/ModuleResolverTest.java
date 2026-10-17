package com.cifa.correlation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModuleResolverTest {

    @Test
    void discoversMavenModulesFromRootPom(@TempDir Path repoRoot) throws Exception {
        Files.writeString(repoRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <modules>
                    <module>module-a</module>
                    <module>module-b</module>
                  </modules>
                </project>
                """);
        Files.createDirectories(repoRoot.resolve("module-a"));
        Files.createDirectories(repoRoot.resolve("module-b"));

        List<Path> roots = ModuleResolver.discoverSourceRoots(repoRoot);

        assertTrue(roots.contains(repoRoot.resolve("src/main/java")));
        assertTrue(roots.contains(repoRoot.resolve("module-a/src/main/java")));
        assertTrue(roots.contains(repoRoot.resolve("module-b/src/main/java")));
    }

    @Test
    void discoversGradleModulesFromSettingsGradle(@TempDir Path repoRoot) throws Exception {
        Files.writeString(repoRoot.resolve("settings.gradle"), "include 'module-a', 'module-b'\n");

        List<Path> roots = ModuleResolver.discoverSourceRoots(repoRoot);

        assertTrue(roots.contains(repoRoot.resolve("module-a/src/main/java")));
        assertTrue(roots.contains(repoRoot.resolve("module-b/src/main/java")));
    }

    @Test
    void discoversGradleKtsModulesWithColonPaths(@TempDir Path repoRoot) throws Exception {
        Files.writeString(repoRoot.resolve("settings.gradle.kts"), "include(\":module-a:sub\")\n");

        List<Path> roots = ModuleResolver.discoverSourceRoots(repoRoot);

        assertTrue(roots.contains(repoRoot.resolve("module-a/sub/src/main/java")));
    }

    @Test
    void fallsBackToRepoRootOnlyWhenNoBuildFilesPresent(@TempDir Path repoRoot) {
        List<Path> roots = ModuleResolver.discoverSourceRoots(repoRoot);

        assertEquals(List.of(repoRoot.resolve("src/main/java")), roots);
    }
}
