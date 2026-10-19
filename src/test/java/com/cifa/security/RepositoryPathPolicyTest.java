package com.cifa.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Direct tests for the path boundary itself (previously untested — the tools'
 * rejection tests only exercised it incidentally). Zero tolerance: absolute
 * paths, escapes and symlink exits are rejected before any file is touched.
 */
class RepositoryPathPolicyTest {

    @TempDir
    Path root;

    @Test
    void rejectsBlankNulAndAbsolutePaths() {
        RepositoryPathPolicy policy = new RepositoryPathPolicy(root);

        assertThrows(IllegalArgumentException.class, () -> policy.resolve(""));
        assertThrows(IllegalArgumentException.class, () -> policy.resolve("   "));
        assertThrows(IllegalArgumentException.class, () -> policy.resolve("a\0b"));
        assertThrows(IllegalArgumentException.class, () -> policy.resolve("/etc/passwd"));
    }

    @Test
    void rejectsEveryEscapeShapeIncludingDeepDotDotChains() {
        RepositoryPathPolicy policy = new RepositoryPathPolicy(root);

        assertThrows(IllegalArgumentException.class, () -> policy.resolve("../outside"));
        assertThrows(IllegalArgumentException.class, () -> policy.resolve("a/../../../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> policy.resolve("a/../.."));
        assertThrows(IllegalArgumentException.class, () -> policy.resolve(Path.of("/tmp/outside")));
    }

    @Test
    void urlShapedValuesStayInsideTheRoot() {
        RepositoryPathPolicy policy = new RepositoryPathPolicy(root);

        // not an absolute filesystem path: it resolves to a weird-but-inside name
        // and is later rejected by the caller's existence check — never escapes
        Path resolved = policy.resolve("https://evil/x");
        assertTrue(resolved.startsWith(policy.root()), resolved.toString());
    }

    @Test
    void normalizationMayMoveAroundInsideTheRootButNeverOut() {
        RepositoryPathPolicy policy = new RepositoryPathPolicy(root);

        // sub/../ cancels sub and stays inside; sub/../../ would escape and is rejected
        Path resolved = policy.resolve("sub/../inside.txt");
        assertTrue(resolved.startsWith(policy.root()));
        assertEquals(root.resolve("inside.txt"), resolved);
        assertThrows(IllegalArgumentException.class, () -> policy.resolve("sub/../../inside.txt"));
    }

    @Test
    void symlinkPointingOutsideIsRejected() throws Exception {
        assumeTrue(symlinksSupported(root));
        Path outside = Files.createTempDirectory("outside-root");
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(root.resolve("link"), outside);
        RepositoryPathPolicy policy = new RepositoryPathPolicy(root);

        assertThrows(IllegalArgumentException.class, () -> policy.resolve("link/secret.txt"));
    }

    @Test
    void symlinkPointingInsideTheRootIsAllowed() throws Exception {
        assumeTrue(symlinksSupported(root));
        Files.createDirectories(root.resolve("real"));
        Files.writeString(root.resolve("real/Target.java"), "class Target {}");
        Files.createSymbolicLink(root.resolve("alias"), root.resolve("real"));
        RepositoryPathPolicy policy = new RepositoryPathPolicy(root);

        Path resolved = policy.resolve("alias/Target.java");
        assertTrue(resolved.startsWith(policy.root()));
    }

    @Test
    void absolutePathInsideTheRootIsAllowedViaThePathOverload() {
        RepositoryPathPolicy policy = new RepositoryPathPolicy(root);

        Path inside = root.resolve("Some.java");
        assertEquals(inside.normalize(), policy.resolve(inside));
        assertThrows(IllegalArgumentException.class, () -> policy.resolve(Path.of("/tmp/outside")));
    }

    private static boolean symlinksSupported(Path dir) {
        try {
            Path probe = dir.resolve(".symlink-probe");
            Files.createSymbolicLink(probe, Path.of("target"));
            Files.deleteIfExists(probe);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
    }
}
