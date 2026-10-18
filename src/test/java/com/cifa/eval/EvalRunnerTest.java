package com.cifa.eval;

import com.cifa.classification.FailureCategory;
import com.cifa.llm.AnalysisResult;
import com.cifa.llm.LLMProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvalRunnerTest {

    @Test
    void runsBaselineAgainstBundledGoldenCases() throws Exception {
        List<GoldenCase> cases = GoldenCase.loadDirectory(Path.of("fixtures/eval"));
        LLMProvider provider = context -> {
            if (context.category() == FailureCategory.UNKNOWN) {
                return new AnalysisResult("UNKNOWN", List.of(), "", 0, false);
            }
            if (context.category() == FailureCategory.DEPENDENCY_BUILD) {
                return new AnalysisResult("cannot find symbol in pricing-lib dependency", List.of("cannot find symbol pricing-lib"), "check dependency", 50, false);
            }
            if (context.category() == FailureCategory.INFRA_FLAKY) {
                return new AnalysisResult("timeout in infrastructure", List.of("SocketTimeoutException"), "retry", 20, true);
            }
            return new AnalysisResult("null customer in UserService", List.of("UserService.java:9"), "check null", 70, false);
        };

        EvalReport report = new EvalRunner().runBaseline(cases, provider);

        assertEquals(cases.size(), report.results().size());
        assertTrue(report.results().stream().allMatch(result -> result.error().isEmpty()));
        // Accuracy values are corpus-dependent; here we only assert they are sane rates.
        assertTrue(report.categoryAccuracy() >= 0.0 && report.categoryAccuracy() <= 1.0);
        assertTrue(report.rootCauseAccuracy() >= 0.0 && report.rootCauseAccuracy() <= 1.0);
        assertTrue(report.evidenceAccuracy() >= 0.0 && report.evidenceAccuracy() <= 1.0);
        assertTrue(report.reasonableUnknownRate() >= 0.0 && report.reasonableUnknownRate() <= 1.0);
        assertTrue(report.toJson().contains("fullSweepRate"));
        assertTrue(report.toMarkdown().contains("Fake responses only"));
    }

    @Test
    void scoresIndependentPathAndInvalidRepeatsBySignature() throws Exception {
        GoldenCase goldenCase = GoldenCase.load(Path.of("fixtures/eval/dependency-missing/case.json"));
        // Same tool invoked twice with different arguments is legitimate; only the
        // verbatim repeat counts as invalid (plan definition).
        EvalObservation observation = new EvalObservation(
                "DEPENDENCY_BUILD", "pricing-lib missing", List.of("pricing-lib"), "pin version", false,
                List.of("dependency_analysis", "dependency_analysis", "git_history"),
                List.of("dependency_analysis(module=app)", "dependency_analysis(module=web)",
                        "git_history(path=README.md)"),
                3, 12, 5, "COMPLETED", "");

        EvalResult result = new EvalRunner().evaluate(goldenCase, observation);

        assertTrue(result.fullSweep());
        assertEquals(1, result.irrelevantToolCalls());
        assertEquals(0, result.repeatedToolCalls(), "different arguments = not an invalid repeat");

        EvalObservation verbatimRepeat = new EvalObservation(
                "DEPENDENCY_BUILD", "pricing-lib missing", List.of("pricing-lib"), "pin version", false,
                List.of("dependency_analysis", "dependency_analysis"),
                List.of("dependency_analysis(module=app)", "dependency_analysis(module=app)"),
                3, 12, 5, "COMPLETED", "");
        assertEquals(1, new EvalRunner().evaluate(goldenCase, verbatimRepeat).repeatedToolCalls());
    }

    @Test
    void correctAnswerWithoutRequiredToolsMustNotPass() throws Exception {
        GoldenCase goldenCase = GoldenCase.load(Path.of("fixtures/eval/dependency-missing/case.json"));

        EvalObservation guessedWell = new EvalObservation(
                "DEPENDENCY_BUILD", "pricing-lib missing", List.of("pricing-lib"), "pin version", false,
                List.of("failure_evidence"), List.of("failure_evidence(keyword=NPE)"), 7, 12, 5, "COMPLETED", "");
        EvalObservation invokedRequired = new EvalObservation(
                "DEPENDENCY_BUILD", "pricing-lib missing", List.of("pricing-lib"), "pin version", false,
                List.of("failure_evidence", "dependency_analysis"),
                List.of("failure_evidence(keyword=NPE)", "dependency_analysis()"), 7, 12, 5, "COMPLETED", "");

        EvalResult guessed = new EvalRunner().evaluate(goldenCase, guessedWell);
        EvalResult honest = new EvalRunner().evaluate(goldenCase, invokedRequired);

        assertTrue(guessed.categoryCorrect() && guessed.rootCauseCorrect() && guessed.evidenceCorrect(),
                "precondition: the guess itself scores well");
        assertFalse(guessed.requiredToolsSatisfied(), "required tool was never invoked");
        assertFalse(guessed.passed(), "guessing the answer must not satisfy a path-sensitive case");
        assertTrue(honest.requiredToolsSatisfied());
        assertTrue(honest.passed());
        assertTrue(goldenCase.requiredTools().contains("dependency_analysis"));
    }

    @Test
    void casesWithoutRequiredToolsAreUnaffected() throws Exception {
        GoldenCase goldenCase = GoldenCase.load(Path.of("fixtures/eval/unknown-insufficient/case.json"));
        EvalObservation observation = new EvalObservation(
                "UNKNOWN", "insufficient diagnostics", List.of(), "", true,
                List.of(), List.of(), 7, 0, 5, "INSUFFICIENT_EVIDENCE", "");

        EvalResult result = new EvalRunner().evaluate(goldenCase, observation);

        assertTrue(result.requiredToolsSatisfied(), "no required tools declared");
        assertTrue(result.passed());
    }
}
