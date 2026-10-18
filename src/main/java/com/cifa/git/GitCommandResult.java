package com.cifa.git;

public record GitCommandResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean outputTruncated
) {
    public GitCommandResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }

    public boolean success() {
        return !timedOut && exitCode == 0;
    }

    public String output() {
        return stdout.isBlank() ? stderr : stdout;
    }
}
