package com.cifa.security;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RepositoryPathPolicy {
    private final Path root;

    public RepositoryPathPolicy(Path repoRoot) {
        if (repoRoot == null) {
            throw new IllegalArgumentException("repository root is required");
        }
        this.root = repoRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.root)) {
            throw new IllegalArgumentException("repository root is not a directory: " + repoRoot);
        }
    }

    public Path root() {
        return root;
    }

    public Path resolve(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("repository path must be non-empty and valid");
        }
        final Path supplied;
        try {
            supplied = Path.of(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("invalid repository path", e);
        }
        if (supplied.isAbsolute()) {
            throw new IllegalArgumentException("absolute repository paths are not allowed");
        }
        Path candidate = root.resolve(supplied).normalize();
        ensureInside(root, candidate);
        ensureNoEscapingLinks(candidate);
        return candidate;
    }

    public Path resolve(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("repository path is required");
        }
        if (!path.isAbsolute()) {
            return resolve(path.toString());
        }
        Path candidate = path.toAbsolutePath().normalize();
        ensureInside(root, candidate);
        ensureNoEscapingLinks(candidate);
        return candidate;
    }

    public String relative(Path path) {
        Path resolved = resolve(path);
        return root.relativize(resolved).toString();
    }

    private void ensureNoEscapingLinks(Path candidate) {
        try {
            Path realRoot = root.toRealPath();
            Path existing = candidate;
            while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing != null) {
                Path realExisting = existing.toRealPath();
                if (!realExisting.startsWith(realRoot)) {
                    throw new IllegalArgumentException("repository path escapes through a symbolic link");
                }
            }
            Path current = root;
            Path relative = root.relativize(candidate);
            for (Path part : relative) {
                current = current.resolve(part);
                if (Files.isSymbolicLink(current)) {
                    Path real = current.toRealPath();
                    if (!real.startsWith(realRoot)) {
                        throw new IllegalArgumentException("repository path escapes through a symbolic link");
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("unable to validate repository path", e);
        }
    }

    private static void ensureInside(Path root, Path candidate) {
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("repository path escapes the repository root");
        }
    }
}
