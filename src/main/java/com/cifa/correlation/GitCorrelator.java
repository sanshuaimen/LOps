package com.cifa.correlation;

import com.cifa.parsing.StackFrame;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class GitCorrelator {

    // ponytail: resolveFilePath below is a pure, filesystem-free flat-layout guess kept
    // for backward compatibility. resolveFilePathAcrossModules is the module-aware,
    // filesystem-checking resolver Main.java actually uses.
    private static final List<String> THIRD_PARTY_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.",
            "org.junit.", "org.springframework.", "org.apache.maven.", "org.mockito."
    );

    private GitCorrelator() {
    }

    public static StackFrame firstProjectFrame(List<StackFrame> frames) {
        for (StackFrame frame : frames) {
            boolean thirdParty = THIRD_PARTY_PREFIXES.stream().anyMatch(frame.className()::startsWith);
            if (!thirdParty) {
                return frame;
            }
        }
        return null;
    }

    // ponytail: assumes a Java-style dotted className (parseJava frames only) — Python/Node
    // frames already carry a file path here, producing a nonsense path; harmless since it
    // just degrades to no correlation, but not worth special-casing for this MVP.
    public static Path resolveFilePath(Path repoRoot, StackFrame frame) {
        String relative = "src/main/java/" + frame.className().replace('.', '/') + ".java";
        return repoRoot.resolve(relative);
    }

    public static Optional<Path> resolveFilePathAcrossModules(Path repoRoot, StackFrame frame) {
        String relative = frame.className().replace('.', '/') + ".java";
        for (Path sourceRoot : ModuleResolver.discoverSourceRoots(repoRoot)) {
            Path candidate = sourceRoot.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return findAnywhereInRepo(repoRoot, frame);
    }

    private static Optional<Path> findAnywhereInRepo(Path repoRoot, StackFrame frame) {
        String simpleName = frame.className().substring(frame.className().lastIndexOf('.') + 1) + ".java";
        if (!Files.isDirectory(repoRoot)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(repoRoot, 15)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(simpleName))
                    .filter(p -> !p.toString().contains(File.separator + ".git" + File.separator))
                    .findFirst();
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
    }

    public static CorrelatedCommit correlate(Path repoRoot, Path filePath, int lineNumber)
            throws IOException, InterruptedException {
        Path relative = repoRoot.relativize(filePath);
        int start = Math.max(1, lineNumber - 3);
        int end = lineNumber + 3;
        String rangeSpec = start + "," + end + ":" + relative;

        ProcessBuilder pb = new ProcessBuilder(
                "git", "-C", repoRoot.toString(), "log", "-1", "--no-color", "-L", rangeSpec
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return null;
        }

        return output.isBlank() ? null : parseGitLogOutput(output);
    }

    static CorrelatedCommit parseGitLogOutput(String output) {
        if (output.isBlank()) {
            return null;
        }
        String[] lines = output.split("\n", -1);
        String hash = null;
        String author = null;
        String date = null;
        StringBuilder diffHunk = new StringBuilder();
        boolean inHunk = false;

        for (String line : lines) {
            if (line.startsWith("commit ")) {
                hash = line.substring("commit ".length()).trim();
            } else if (line.startsWith("Author: ")) {
                author = line.substring("Author: ".length()).trim();
            } else if (line.startsWith("Date:")) {
                date = line.substring("Date:".length()).trim();
            } else if (line.startsWith("@@")) {
                inHunk = true;
            }
            if (inHunk) {
                diffHunk.append(line).append('\n');
            }
        }

        if (hash == null) {
            return null;
        }
        return new CorrelatedCommit(hash, author, date, diffHunk.toString().stripTrailing());
    }
}
