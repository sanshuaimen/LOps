package com.cifa.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A single piece of registered evidence. Instances are only created through
 * {@link EvidenceCatalog#register}, so the id and content hash can never be
 * supplied by the model.
 */
public record Evidence(
        String id,
        EvidenceType type,
        String locator,
        String content,
        String contentHash,
        String toolName,
        int startLine,
        int endLine,
        String commit
) {
    public static final int NO_LINE = -1;

    public Evidence {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("evidence id is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("evidence type is required");
        }
        locator = locator == null ? "" : locator;
        content = content == null ? "" : content;
        contentHash = contentHash == null || contentHash.isBlank() ? sha256(content) : contentHash;
        toolName = toolName == null ? "" : toolName;
        commit = commit == null ? "" : commit;
    }

    public boolean hasLineRange() {
        return startLine >= 1;
    }

    public boolean hasCommit() {
        return !commit.isBlank();
    }

    /** Hash of the quoted content; used by {@link EvidenceVerifier} to check citations. */
    public boolean contentMatches(String quoted) {
        return sha256(quoted == null ? "" : quoted).equals(contentHash);
    }

    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
