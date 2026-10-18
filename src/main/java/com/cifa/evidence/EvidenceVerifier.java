package com.cifa.evidence;

import com.cifa.security.RepositoryPathPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Layer 1 of conclusion control: deterministic authenticity checks on cited
 * evidence. This establishes that citations are real, belong to the task, and
 * are internally consistent. It says nothing about whether the root cause is
 * semantically correct — that is layer 2.
 */
public final class EvidenceVerifier {

    private static final Pattern ID_FORMAT = Pattern.compile("[A-Z]+-\\d{3,}");
    private static final Pattern COMMIT_FORMAT = Pattern.compile("[0-9a-f]{7,40}");
    private static final Pattern LOCATOR_RANGE = Pattern.compile("(.*):(\\d+)-(\\d+)");

    private final EvidenceCatalog catalog;
    private final RepositoryPathPolicy paths;

    public EvidenceVerifier(EvidenceCatalog catalog, RepositoryPathPolicy paths) {
        this.catalog = catalog;
        this.paths = paths;
    }

    public record Violation(String code, String detail) {
        @Override
        public String toString() {
            return code + ": " + detail;
        }
    }

    /** Verifies the evidence ids a diagnosis cites. Fabricated ids are rejected here. */
    public List<Violation> verifyCitations(List<String> citedIds) {
        List<Violation> violations = new ArrayList<>();
        if (citedIds == null || citedIds.isEmpty()) {
            return List.of(new Violation("NO_EVIDENCE", "diagnosis cites no evidence"));
        }
        for (String id : citedIds) {
            if (id == null || !ID_FORMAT.matcher(id.trim()).matches()) {
                violations.add(new Violation("MALFORMED_ID", "not an evidence id: " + id));
                continue;
            }
            // The catalog is task-scoped, so ids from other tasks fail here as unknown.
            Evidence evidence = catalog.find(id).orElse(null);
            if (evidence == null) {
                violations.add(new Violation("UNKNOWN_EVIDENCE", "no such evidence in this task: " + id));
                continue;
            }
            violations.addAll(checkShape(evidence));
        }
        return List.copyOf(violations);
    }

    /** Integrity sweep over everything registered so far; used by eval and debug tooling. */
    public List<Violation> verifyIntegrity() {
        List<Violation> violations = new ArrayList<>();
        for (Evidence evidence : catalog.all()) {
            if (evidence.toolName().isBlank()) {
                violations.add(new Violation("UNREGISTERED_TOOL",
                        evidence.id() + " has no registering tool"));
            }
            if (!evidence.contentMatches(evidence.content())) {
                violations.add(new Violation("HASH_MISMATCH",
                        evidence.id() + " content does not match its hash"));
            }
            violations.addAll(checkShape(evidence));
        }
        return List.copyOf(violations);
    }

    private List<Violation> checkShape(Evidence evidence) {
        List<Violation> violations = new ArrayList<>();
        switch (evidence.type()) {
            case LOG -> {
                if (!evidence.hasLineRange() || evidence.endLine() < evidence.startLine()) {
                    violations.add(new Violation("MISSING_LOG_RANGE",
                            evidence.id() + " must carry a real line range"));
                }
            }
            case SOURCE -> {
                if (evidence.locator().startsWith("symbol:")) {
                    break;
                }
                var range = LOCATOR_RANGE.matcher(evidence.locator());
                if (!range.matches()) {
                    violations.add(new Violation("INVALID_SOURCE_LOCATOR",
                            evidence.id() + " locator must be path:start-end"));
                    break;
                }
                if (evidence.startLine() < 1 || evidence.endLine() < evidence.startLine()) {
                    violations.add(new Violation("INVALID_SOURCE_LINES",
                            evidence.id() + " line numbers must be positive and ordered"));
                }
                if (paths != null) {
                    try {
                        paths.resolve(range.group(1));
                    } catch (IllegalArgumentException e) {
                        violations.add(new Violation("SOURCE_PATH_ESCAPES",
                                evidence.id() + " path outside repository: " + range.group(1)));
                    }
                }
            }
            case GIT -> {
                if (!evidence.hasCommit() || !COMMIT_FORMAT.matcher(evidence.commit()).matches()) {
                    violations.add(new Violation("MISSING_COMMIT",
                            evidence.id() + " must carry a real commit id"));
                }
            }
            default -> {
                // TEST_REPORT / DEPENDENCY / FLAKY_HISTORY: locator + tool registration suffice.
            }
        }
        return List.copyOf(violations);
    }
}
