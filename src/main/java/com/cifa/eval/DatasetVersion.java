package com.cifa.eval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Corpus manifest hash: the "dataset version" stamped into versioned reports.
 * Hashes the semantic manifest (id / layer / category / unknown-flag / required
 * tools), not fixture bytes — log wording tweaks that do not move the labeled
 * manifest keep reports comparable.
 */
public final class DatasetVersion {

    private DatasetVersion() {
    }

    public static String of(List<GoldenCase> cases) {
        String manifest = cases.stream()
                .sorted(Comparator.comparing(GoldenCase::id))
                .map(c -> c.id() + "|" + c.layer() + "|" + c.expectedCategory() + "|"
                        + c.shouldBeUnknown() + "|" + String.join(",", c.requiredTools()))
                .collect(Collectors.joining("\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(manifest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
