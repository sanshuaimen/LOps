package com.cifa.cli;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Tier-1 corpus expansion harness (the data-flywheel plan): inject a KNOWN
 * fault into a copy of a real, building project, optionally run a real build
 * to capture a REAL failure log, and emit a golden-case skeleton whose gold
 * label falls out of the injection for free — no human triage.
 *
 * <p>Why injection beats synthetic logs: the log shape stays 100% real (the
 * parser gaps the corpus must honestly expose live here, e.g. Gradle's
 * frame-less output), and the label is ground truth by construction. The
 * reproducibility contract is satisfied by pinning the mutated copy itself:
 * the emitted case directory carries the full mutated repo, so commit +
 * toolchain + patch are all baked into the artifact.</p>
 *
 * <p>Dry-run mode materializes everything except the build — usable offline;
 * the real run needs a local toolchain (mvn), exactly like real-model eval:
 * a local manual command, never CI.</p>
 */
public final class GoldenCaseInjector {

    public enum Fault {
        /** Adds a nonexistent dependency → resolution/build failure (DEPENDENCY_BUILD). */
        DEPENDENCY_VERSION("DEPENDENCY_BUILD", List.of("ghost-artifact", "nonexistent")),
        /** Adds a bogus import → compile error (DEPENDENCY_BUILD: the build refused artifacts). */
        BROKEN_IMPORT("DEPENDENCY_BUILD", List.of("cannot find symbol", "GhostSymbol")),
        /** Appends a random-failing JUnit test → intermittent failure (INFRA_FLAKY). */
        FLAKY_TEST("INFRA_FLAKY", List.of("cifaInjectedFlakyTest"));

        final String goldCategory;
        final List<String> goldKeywords;

        Fault(String goldCategory, List<String> goldKeywords) {
            this.goldCategory = goldCategory;
            this.goldKeywords = List.copyOf(goldKeywords);
        }
    }

    public record Result(Path caseDir, List<String> mutatedFiles, String goldCategory,
                         List<String> goldKeywords, Path buildLog, boolean built) {
    }

    private final Fault fault;

    private GoldenCaseInjector(Fault fault) {
        this.fault = fault;
    }

    public static GoldenCaseInjector forFault(String name) {
        try {
            return new GoldenCaseInjector(Fault.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_')));
        } catch (IllegalArgumentException e) {
            StringBuilder names = new StringBuilder();
            for (Fault fault : Fault.values()) {
                if (names.length() > 0) {
                    names.append("|");
                }
                names.append(fault.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            }
            throw new MemoryCommand.Usage("unknown --fault '" + name + "' — expected " + names, false);
        }
    }

    /** Copies the repo, applies the mutation, writes the case skeleton, and (unless
     * dry-run) builds the MUTATED copy for a real log. The original repo is never touched. */
    public Result inject(Path repo, String value, Path outRoot, boolean dryRun) throws IOException, InterruptedException {
        String repoName = repo.getFileName().toString();
        String caseId = repoName + "-" + fault.name().toLowerCase(Locale.ROOT).replace('_', '-');
        Path caseDir = outRoot.resolve(caseId);
        Path mutated = caseDir.resolve("repo");
        if (Files.exists(caseDir)) {
            throw new MemoryCommand.Usage("case dir already exists: " + caseDir + " — remove it or pick another --out", false);
        }
        copyTree(repo, mutated);

        List<String> mutatedFiles = switch (fault) {
            case DEPENDENCY_VERSION -> List.of(injectGhostDependency(mutated, value));
            case BROKEN_IMPORT -> List.of(injectBrokenImport(mutated));
            case FLAKY_TEST -> List.of(injectFlakyTest(mutated));
        };

        if (!dryRun) {
            runBuild(mutated, caseDir.resolve("build.log"));
        }

        JSONObject caseJson = new JSONObject();
        caseJson.put("layer", "robustness");
        caseJson.put("id", caseId);
        caseJson.put("expectedCategory", fault.goldCategory);
        caseJson.put("rootCauseKeywords", new org.json.JSONArray(fault.goldKeywords));
        caseJson.put("injectedFault", fault.name());
        caseJson.put("provenance", "golden-case-injector: known fault, gold label by construction");
        Files.writeString(caseDir.resolve("case.json"), caseJson.toString(2), StandardCharsets.UTF_8);

        Path buildLog = dryRun ? null : caseDir.resolve("build.log");
        return new Result(caseDir, mutatedFiles, fault.goldCategory, fault.goldKeywords, buildLog, !dryRun);
    }

    /** Appends a nonexistent dependency — mvn fails at resolution, honestly DEPENDENCY_BUILD. */
    private static String injectGhostDependency(Path repoRoot, String value) throws IOException {
        Path pom = repoRoot.resolve("pom.xml");
        if (!Files.exists(pom)) {
            throw new MemoryCommand.Usage("no pom.xml at repo root — DEPENDENCY_VERSION injection needs a Maven project", false);
        }
        String artifact = value == null || value.isBlank() ? "ghost-artifact" : value;
        String pomxml = Files.readString(pom, StandardCharsets.UTF_8);
        String block = "    <dependency>\n"
                + "      <groupId>com.example.cifa.nonexistent</groupId>\n"
                + "      <artifactId>" + artifact + "</artifactId>\n"
                + "      <version>9999.0.0</version>\n"
                + "    </dependency>\n";
        int insertAt = pomxml.lastIndexOf("</dependencies>");
        String mutated = insertAt >= 0
                ? pomxml.substring(0, insertAt) + block + pomxml.substring(insertAt)
                : pomxml.replace("</project>", "  <dependencies>\n" + block + "  </dependencies>\n</project>");
        Files.writeString(pom, mutated, StandardCharsets.UTF_8);
        return "pom.xml";
    }

    /** Inserts a bogus import into the first main-source file — javac refuses. */
    private static String injectBrokenImport(Path repoRoot) throws IOException {
        Path source = firstFile(repoRoot.resolve("src/main/java"), ".java");
        if (source == null) {
            throw new MemoryCommand.Usage("no .java source under src/main/java — BROKEN_IMPORT needs a standard Maven layout", false);
        }
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        List<String> mutated = new ArrayList<>(lines);
        int insertAt = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("package ")) {
                insertAt = i + 1;
                break;
            }
        }
        mutated.add(insertAt, "import com.cifa.inject.does.not.exist.GhostSymbol;");
        Files.write(source, mutated, StandardCharsets.UTF_8);
        return repoRoot.relativize(source).toString();
    }

    /** Appends a random-failing JUnit 5 test — the flaky class by construction. */
    private static String injectFlakyTest(Path repoRoot) throws IOException {
        Path test = firstFile(repoRoot.resolve("src/test/java"), "Test.java");
        if (test == null) {
            throw new MemoryCommand.Usage("no *Test.java under src/test/java — FLAKY_TEST needs an existing JUnit test", false);
        }
        String content = Files.readString(test, StandardCharsets.UTF_8);
        String method = "\n    @org.junit.jupiter.api.Test\n"
                + "    void cifaInjectedFlakyTest() {\n"
                + "        org.junit.jupiter.api.Assertions.assertTrue(\n"
                + "                java.util.concurrent.ThreadLocalRandom.current().nextBoolean(),\n"
                + "                \"injected flaky: fails randomly by construction\");\n"
                + "    }\n";
        int lastBrace = content.lastIndexOf('}');
        if (lastBrace < 0) {
            throw new MemoryCommand.Usage("cannot parse test class " + test + " (no closing brace)", false);
        }
        Files.writeString(test, content.substring(0, lastBrace) + method + content.substring(lastBrace),
                StandardCharsets.UTF_8);
        return repoRoot.relativize(test).toString();
    }

    /** Real build of the MUTATED copy — the whole point: the log shape is genuine. */
    private static void runBuild(Path repoRoot, Path logFile) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("mvn", "package", "-q")
                .directory(repoRoot.toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        List<String> output = process.inputReader().lines().toList();
        int exit = process.waitFor();
        List<String> withHeader = new ArrayList<>();
        withHeader.add("# injected-case build log (mvn package, exit=" + exit + ")");
        withHeader.addAll(output);
        Files.write(logFile, withHeader, StandardCharsets.UTF_8);
    }

    private static Path firstFile(Path root, String suffixOrName) throws IOException {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> suffixOrName.startsWith(".")
                            ? path.getFileName().toString().endsWith(suffixOrName)
                            : path.getFileName().toString().endsWith(suffixOrName))
                    .sorted(Comparator.naturalOrder())
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            walk.forEach(source -> {
                Path target = to.resolve(from.relativize(source).toString());
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static final class UncheckedIOException extends RuntimeException {
        UncheckedIOException(IOException cause) {
            super(cause);
        }
    }
}
