package com.cifa.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GitCommandExecutorTest {

    @Test
    void executesFixedLogTemplateWithArrayArguments(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("A.txt"), "one\n");
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        git(repo, "add", "A.txt");
        git(repo, "commit", "-q", "-m", "initial");

        GitCommandResult result = new GitCommandExecutor(repo).log(repo.resolve("A.txt"), 5);

        assertTrue(result.success(), result.stderr());
        assertTrue(result.stdout().contains("initial"));
        assertFalse(result.stdout().contains("--help"));
    }

    @Test
    void rejectsEscapingPathsAndOptionLikeRevisions(@TempDir Path repo) throws Exception {
        GitCommandExecutor executor = new GitCommandExecutor(repo);
        assertThrows(IllegalArgumentException.class, () -> executor.log(Path.of("../outside"), 1));
        assertThrows(IllegalArgumentException.class, () -> executor.log(Path.of("/tmp/outside"), 1));
        assertThrows(IllegalArgumentException.class, () -> executor.show("--help", Path.of("A.txt")));
        assertThrows(IllegalArgumentException.class, () -> executor.show("HEAD..main", Path.of("A.txt")));
    }

    @Test
    void rejectsSymlinkEscapeAndCapsOutput(@TempDir Path repo) throws Exception {
        Path outside = Files.createTempDirectory("git-outside");
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(repo.resolve("link"), outside);
        GitCommandExecutor executor = new GitCommandExecutor(repo, Duration.ofSeconds(5), 4);
        assertThrows(IllegalArgumentException.class, () -> executor.log(Path.of("link/secret.txt"), 1));

        Files.writeString(repo.resolve("large.txt"), "not a git repository output");
        GitCommandResult result = executor.log(repo.resolve("large.txt"), 1);
        assertTrue(result.output().length() <= 100);
    }

    @Test
    void reportsTimeoutAsStructuredFailure(@TempDir Path repo) throws Exception {
        GitCommandExecutor executor = new GitCommandExecutor(repo, Duration.ofNanos(1), 100);
        GitCommandResult result = executor.log(Path.of("missing.txt"), 1);
        assertTrue(result.timedOut());
        assertFalse(result.success());
        assertEquals(-1, result.exitCode());
    }

    @Test
    void interruptsSurfaceAsInterruptedException(@TempDir Path repo) throws Exception {
        GitCommandExecutor executor = new GitCommandExecutor(repo);
        Thread.currentThread().interrupt();
        try {
            // process.waitFor responds to the pre-set flag by throwing immediately
            assertThrows(InterruptedException.class, () -> executor.log(Path.of("A.txt"), 1));
            // waitFor consumed the flag when it threw; the test thread must stay clean
            assertFalse(Thread.interrupted(), "interrupt flag must be cleared after the throw");
        } finally {
            Thread.interrupted(); // defensive: never leak a flag into other tests
        }
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
}
