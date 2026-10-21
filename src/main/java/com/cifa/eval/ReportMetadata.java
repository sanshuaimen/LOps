package com.cifa.eval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Versioning block stamped into every versioned comparison report: which corpus
 * (manifest hash), which code (git short sha), which provider, which run.
 * generatedAt and runId are the only fields allowed to differ between two
 * otherwise-identical fake runs (reproducibility contract).
 */
public record ReportMetadata(
        String datasetVersion,
        String codeVersion,
        String provider,
        String runId,
        Instant generatedAt) {

    public static ReportMetadata forCorpus(List<GoldenCase> cases, String provider) {
        return new ReportMetadata(DatasetVersion.of(cases), CodeVersion.shortSha(),
                provider, newRunId(), Instant.now());
    }

    /** Placeholder for ad-hoc/unversioned renders (tests, quick diffs). */
    public static ReportMetadata adhoc() {
        return new ReportMetadata("unversioned", CodeVersion.shortSha(), "fake", "adhoc", Instant.now());
    }

    public static String newRunId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** comparison-<datasetVersion>-<provider>-<runId>.md */
    public String fileName() {
        return "comparison-" + datasetVersion + "-" + provider + "-" + runId + ".md";
    }
}
