package com.cifa.git;

import com.cifa.security.RepositoryPathPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class GitCommandExecutor {
    private static final int MAX_REVISION_LENGTH = 128;
    private final Path repoRoot;
    private final RepositoryPathPolicy paths;
    private final Duration timeout;
    private final int maxOutputChars;

    public GitCommandExecutor(Path repoRoot) {
        this(repoRoot, Duration.ofSeconds(30), 100_000);
    }

    public GitCommandExecutor(Path repoRoot, Duration timeout, int maxOutputChars) {
        this.paths = new RepositoryPathPolicy(repoRoot);
        this.repoRoot = paths.root();
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("git timeout must be positive");
        }
        if (maxOutputChars <= 0) {
            throw new IllegalArgumentException("git output limit must be positive");
        }
        this.timeout = timeout;
        this.maxOutputChars = maxOutputChars;
    }

    public GitCommandResult logLineRange(Path filePath, int startLine, int endLine)
            throws IOException, InterruptedException {
        String relative = paths.relative(filePath);
        validateLineRange(startLine, endLine);
        String range = startLine + "," + endLine + ":" + relative;
        return execute(List.of("git", "-C", repoRoot.toString(), "log", "-1", "--no-color", "-L", range));
    }

    public GitCommandResult log(Path filePath, int limit) throws IOException, InterruptedException {
        String relative = paths.relative(filePath);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("git log limit must be between 1 and 100");
        }
        return execute(List.of("git", "-C", repoRoot.toString(), "log", "--no-color",
                "--max-count=" + limit, "--format=fuller", "--", relative));
    }

    public GitCommandResult show(String revision, Path filePath) throws IOException, InterruptedException {
        String validRevision = validateRevision(revision);
        String relative = paths.relative(filePath);
        return execute(List.of("git", "-C", repoRoot.toString(), "show", "--no-color",
                "--format=fuller", validRevision, "--", relative));
    }

    public GitCommandResult blame(Path filePath, int startLine, int endLine)
            throws IOException, InterruptedException {
        String relative = paths.relative(filePath);
        validateLineRange(startLine, endLine);
        // blame has no --no-color on this git; piped output is never colorized anyway.
        return execute(List.of("git", "-C", repoRoot.toString(), "blame",
                "-L", startLine + "," + endLine, "--", relative));
    }

    public GitCommandResult diff(String revision, Path filePath) throws IOException, InterruptedException {
        String validRevision = validateRevision(revision);
        String relative = paths.relative(filePath);
        return execute(List.of("git", "-C", repoRoot.toString(), "diff", "--no-color",
                validRevision, "--", relative));
    }

    public Path repoRoot() {
        return repoRoot;
    }

    static String validateRevision(String revision) {
        if (revision == null || revision.isBlank() || revision.length() > MAX_REVISION_LENGTH
                || revision.startsWith("-") || revision.contains("..")
                || !revision.matches("[A-Za-z0-9][A-Za-z0-9._/@^~{}-]*")) {
            throw new IllegalArgumentException("invalid or unsupported git revision");
        }
        return revision;
    }

    private static void validateLineRange(int startLine, int endLine) {
        if (startLine < 1 || endLine < startLine || endLine - startLine > 10_000) {
            throw new IllegalArgumentException("invalid git line range");
        }
    }

    private GitCommandResult execute(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(new ArrayList<>(command)).start();
        AtomicReference<LimitedOutput> stdout = new AtomicReference<>(new LimitedOutput("", false));
        AtomicReference<LimitedOutput> stderr = new AtomicReference<>(new LimitedOutput("", false));
        Thread outReader = reader(process.getInputStream(), stdout, "git-stdout");
        Thread errReader = reader(process.getErrorStream(), stderr, "git-stderr");
        outReader.start();
        errReader.start();
        long timeoutNanos = Math.max(1, timeout.toNanos());
        long startNanos = System.nanoTime();
        boolean finished = process.waitFor(timeoutNanos, TimeUnit.NANOSECONDS);
        // The child may exit before waitFor is even entered; anything not strictly inside
        // the budget is reported as a timeout so the caller gets a structured failure.
        boolean timedOut = !finished || System.nanoTime() - startNanos >= timeoutNanos;
        if (timedOut) {
            process.destroy();
            if (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        }
        joinReaders(outReader, errReader, process);
        int exitCode = timedOut ? -1 : process.exitValue();
        LimitedOutput out = stdout.get();
        LimitedOutput err = stderr.get();
        return new GitCommandResult(exitCode, out.text(), err.text(), timedOut,
                out.truncated() || err.truncated());
    }

    private Thread reader(InputStream input, AtomicReference<LimitedOutput> target, String name) {
        Thread thread = new Thread(() -> {
            try (InputStream stream = input) {
                target.set(readLimited(stream, maxOutputChars));
            } catch (IOException e) {
                target.set(new LimitedOutput("git output read failed: " + e.getMessage(), false));
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void joinReaders(Thread outReader, Thread errReader, Process process)
            throws InterruptedException {
        outReader.join(1000);
        errReader.join(1000);
        if (outReader.isAlive() || errReader.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static LimitedOutput readLimited(InputStream input, int maxChars) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxChars, 8192));
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            int remaining = maxChars - bytes.size();
            if (remaining > 0) {
                bytes.write(buffer, 0, Math.min(read, remaining));
            }
            if (read > remaining) {
                truncated = true;
            }
        }
        return new LimitedOutput(bytes.toString(StandardCharsets.UTF_8), truncated);
    }

    private record LimitedOutput(String text, boolean truncated) {
    }
}
