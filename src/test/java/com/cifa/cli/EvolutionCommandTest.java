package com.cifa.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CLI smoke: the control surface actually drives the stores — partition writes
 * the double-blind split, list/decay/snapshot speak the store's language, and
 * the inject harness emits a golden-case skeleton whose gold label falls out
 * of the fault by construction.
 */
class EvolutionCommandTest {

    @TempDir
    Path temp;

    private static String run(String[] args, Map<String, String> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = EvolutionCommand.run(args, new java.io.PrintStream(out),
                new java.io.PrintStream(err), env);
        return "exit=" + exit + "\n--out--\n" + out + "--err--\n" + err;
    }

    private Path miniFixtures() throws Exception {
        Path fixtures = temp.resolve("fixtures");
        for (String id : List.of("case-1", "case-2", "case-3", "case-4")) {
            Path dir = fixtures.resolve(id);
            Files.createDirectories(dir.resolve("repo"));
            Files.writeString(dir.resolve("build.log"),
                    "java.lang.IllegalStateException: boom\n", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("case.json"), "{\"layer\":\"robustness\",\"id\":\"" + id
                    + "\",\"expectedCategory\":\"LOGIC_ERROR\"}", StandardCharsets.UTF_8);
        }
        return fixtures;
    }

    private Path miniRepo() throws Exception {
        Path repo = temp.resolve("mini-maven");
        Files.createDirectories(repo.resolve("src/main/java/com/example"));
        Files.createDirectories(repo.resolve("src/test/java/com/example"));
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>mini</artifactId><version>1.0</version>
                  <dependencies><dependencies-placeholder/>
                  </dependencies>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("src/main/java/com/example/Thing.java"), """
                package com.example;
                public class Thing { int one() { return 1; } }
                """, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("src/test/java/com/example/ThingTest.java"), """
                package com.example;
                class ThingTest {
                  void one() {}
                }
                """, StandardCharsets.UTF_8);
        return repo;
    }

    @Test
    void partitionCommandWritesTheDoubleBlindSplit() throws Exception {
        Path fixtures = miniFixtures();
        String result = run(new String[]{"partition", "--fixtures", fixtures.toString(),
                "--out", temp.resolve("partition.json").toString(), "--seed", "cli-seed"}, Map.of());
        assertTrue(result.contains("exit=0"), result);
        assertTrue(result.contains("holdout="), result);
        assertTrue(Files.exists(temp.resolve("partition.json")), result);
    }

    @Test
    void unknownFaultIsAUsageErrorNotACrash() {
        String result = run(new String[]{"inject", "--repo", temp.toString(), "--fault", "no-such-fault"},
                Map.of());
        assertTrue(result.contains("exit=2"), result);
        assertTrue(result.contains("expected dependency-version|broken-import|flaky-test"), result);
    }

    @Test
    void injectDryRunEmitsCaseSkeletonWithFreeGoldLabel() throws Exception {
        Path repo = miniRepo();
        Path out = temp.resolve("injected");
        String result = run(new String[]{"inject", "--repo", repo.toString(),
                "--fault", "dependency-version", "--out", out.toString(), "--dry-run"}, Map.of());

        assertTrue(result.contains("exit=0"), result);
        assertTrue(result.contains("DEPENDENCY_BUILD"), result);
        Path caseDir = out.resolve("mini-maven-dependency-version");
        Path caseJson = caseDir.resolve("case.json");
        assertTrue(Files.exists(caseJson), result);
        String json = Files.readString(caseJson);
        assertTrue(json.contains("ghost-artifact"), json);
        assertTrue(json.contains("provenance"), json);
        // the mutated copy carries the ghost dependency; the ORIGINAL repo is untouched
        String mutatedPom = Files.readString(caseDir.resolve("repo/pom.xml"));
        assertTrue(mutatedPom.contains("com.example.cifa.nonexistent"), mutatedPom);
        assertFalse(Files.readString(repo.resolve("pom.xml")).contains("nonexistent"));
        assertTrue(result.contains("dry-run: build NOT executed"), result);
    }

    @Test
    void brokenImportInjectionMutatesARealSourceFile() throws Exception {
        Path repo = miniRepo();
        Path out = temp.resolve("injected-import");
        String result = run(new String[]{"inject", "--repo", repo.toString(),
                "--fault", "broken-import", "--out", out.toString(), "--dry-run"}, Map.of());
        assertTrue(result.contains("exit=0"), result);
        assertTrue(result.contains("cannot find symbol"), result);
        Path mutatedSource = out.resolve("mini-maven-broken-import/repo/src/main/java/com/example/Thing.java");
        assertTrue(Files.readString(mutatedSource).contains("import com.cifa.inject.does.not.exist.GhostSymbol;"));
    }

    @Test
    void listCommandReportsStoreCounts() throws Exception {
        Path skills = temp.resolve("skills.json");
        new com.cifa.evolution.SkillsStore(skills,
                new com.cifa.evolution.SkillAuditLog(temp.resolve("a.jsonl")))
                .append(new com.cifa.evolution.SkillRule("skill-cli001", "p", com.cifa.evolution.SkillKind.PROMPT,
                        "LOGIC_ERROR", java.util.List.of("timeout"), java.util.List.of(), "check timeouts",
                        com.cifa.evolution.SkillStatus.CANDIDATE, "", "", "t", java.util.List.of(),
                        java.util.List.of(), "", 0, 0, ""), "test", "t");

        String result = run(new String[]{"list", "--skills", skills.toString()}, Map.of());
        assertTrue(result.contains("exit=0"), result);
        assertTrue(result.contains("skill-cli001"), result);
        assertTrue(result.contains("total: 1 skills, 0 active"), result);
    }

    @Test
    void decayCommandRefusesAnUndergrownLibraryWithTheHonestMessage() throws Exception {
        Path skills = temp.resolve("skills-decay.json");
        new com.cifa.evolution.SkillsStore(skills,
                new com.cifa.evolution.SkillAuditLog(temp.resolve("a2.jsonl")))
                .append(new com.cifa.evolution.SkillRule("skill-dec001", "p", com.cifa.evolution.SkillKind.PROMPT,
                        "LOGIC_ERROR", java.util.List.of("timeout"), java.util.List.of(), "check",
                        com.cifa.evolution.SkillStatus.CANDIDATE, "", "", "t", java.util.List.of(),
                        java.util.List.of(), "", 0, 0, ""), "test", "t");
        Path usage = temp.resolve("usage.json");

        String result = run(new String[]{"decay", "--skills", skills.toString(),
                "--usage", usage.toString(), "--min-store", "10"}, Map.of());
        assertTrue(result.contains("exit=1"), result);
        assertTrue(result.contains("decay refused"), result);
    }
}
