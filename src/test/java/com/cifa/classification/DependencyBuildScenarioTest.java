package com.cifa.classification;

import com.cifa.correlation.GitCorrelator;
import com.cifa.ingestion.LogNormalizer;
import com.cifa.llm.AnalysisResult;
import com.cifa.parsing.FailureLogParser;
import com.cifa.parsing.ParsedFailure;
import com.cifa.parsing.StackFrame;
import com.cifa.report.MarkdownRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyBuildScenarioTest {

    private static final String DEPENDENCY_FAILURE_LOG =
            "[ERROR] Failed to execute goal on project sample-app: Could not resolve dependencies for project "
            + "com.example:sample-app:jar:1.0.0: Could not find artifact com.example:pricing-lib:jar:2.3.0 "
            + "in central (https://repo.maven.apache.org/maven2)\n"
            + "[INFO] BUILD FAILURE\n";

    @Test
    void routesDependencyResolutionFailureToDependencyBuildCategory() {
        String normalized = LogNormalizer.normalize(DEPENDENCY_FAILURE_LOG);
        ParsedFailure failure = FailureLogParser.parse(normalized);
        ClassifiedFailure classified = FailureClassifier.classify(failure);

        assertEquals(FailureCategory.DEPENDENCY_BUILD, classified.category());
    }

    @Test
    void dependencyBuildFailuresStillAttemptCorrelationButFindNoFrame() {
        ParsedFailure failure = FailureLogParser.parse(LogNormalizer.normalize(DEPENDENCY_FAILURE_LOG));
        ClassifiedFailure classified = FailureClassifier.classify(failure);

        // Main.java only skips the correlation block for INFRA_FLAKY — confirm this
        // category is not skipped, and that (correctly) no frame is available to correlate.
        assertNotEquals(FailureCategory.INFRA_FLAKY, classified.category());
        StackFrame projectFrame = GitCorrelator.firstProjectFrame(failure.frames());
        assertNull(projectFrame);
    }

    @Test
    void reportOmitsInfraFlakyBannerForDependencyBuildCategory() {
        AnalysisResult result = new AnalysisResult(
                "Missing artifact com.example:pricing-lib:jar:2.3.0 in the dependency tree",
                List.of("Could not find artifact com.example:pricing-lib:jar:2.3.0"),
                "Publish or pin an available version of pricing-lib, or add the correct repository.",
                55,
                false
        );

        String report = new MarkdownRenderer().render(FailureCategory.DEPENDENCY_BUILD, result, List.of());

        assertFalse(report.contains("Low confidence"));
    }
}
