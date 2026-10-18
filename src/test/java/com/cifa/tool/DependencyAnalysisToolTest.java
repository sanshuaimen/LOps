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

class DependencyAnalysisToolTest {

    @Test
    void analyzesModulesAndDetectsVersionConflicts(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>service</module>
                    <module>web</module>
                  </modules>
                </project>
                """);
        Path service = Files.createDirectories(repo.resolve("service"));
        Files.writeString(service.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <artifactId>service</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>31.1-jre</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path web = Files.createDirectories(repo.resolve("web"));
        Files.writeString(web.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <artifactId>web</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>29.0-jre</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>service</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        DependencyAnalysisTool tool = new DependencyAnalysisTool(catalog, new RepositoryPathPolicy(repo));

        ToolResult result = tool.execute(Map.of());

        assertEquals(ToolStatus.OK, result.status(), result.message());
        assertTrue(result.message().contains("modules (3)"), result.message());
        assertTrue(result.message().contains("version conflict for com.google.guava:guava"), result.message());
        assertTrue(result.message().contains("29.0-jre") && result.message().contains("31.1-jre"),
                result.message());
        assertTrue(result.message().contains("dependency without explicit version"), result.message());
        var evidence = catalog.find(result.evidenceIds().get(0)).orElseThrow();
        assertEquals(EvidenceType.DEPENDENCY, evidence.type());
        assertEquals("pom-analysis", evidence.locator());
    }

    @Test
    void answersNoDataWithoutPoms(@TempDir Path repo) {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        DependencyAnalysisTool tool = new DependencyAnalysisTool(catalog, new RepositoryPathPolicy(repo));

        assertEquals(ToolStatus.NO_DATA, tool.execute(Map.of()).status());
        assertEquals(0, catalog.size());
    }

    @Test
    void skipsUnparsablePomsButSurvives(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("pom.xml"), "<project><artifactId>ok</artifactId></project>");
        Path broken = Files.createDirectories(repo.resolve("broken"));
        // DOCTYPE is rejected by the safe parser; the tool must skip it, not crash.
        Files.writeString(broken.resolve("pom.xml"), "<!DOCTYPE project [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><project/>");

        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        DependencyAnalysisTool tool = new DependencyAnalysisTool(catalog, new RepositoryPathPolicy(repo));

        ToolResult result = tool.execute(Map.of());
        assertEquals(ToolStatus.OK, result.status(), result.message());
        assertTrue(result.message().contains("modules (1)"));
    }
}
