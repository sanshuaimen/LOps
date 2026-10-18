package com.cifa.eval;

import com.cifa.classification.FailureCategory;
import com.cifa.llm.FakeLLMProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the golden-case corpus itself: every fixture must load, carry a
 * consistent label set, and run through the baseline eval pipeline without
 * exceptions. Fixture expansions (P0 target: 30-50 cases) must keep this green.
 */
class EvalCorpusTest {

    private static final Path FIXTURES = Path.of("fixtures/eval");

    @Test
    void loadsEveryGoldenCaseInTheCorpus() throws Exception {
        List<GoldenCase> cases = GoldenCase.loadDirectory(FIXTURES);

        assertTrue(cases.size() >= 12, "expected >= 12 golden cases, found " + cases.size());
        assertTrue(cases.stream().noneMatch(GoldenCase -> GoldenCase.id().isBlank()));

        Set<String> ids = cases.stream().map(GoldenCase::id).collect(Collectors.toSet());
        assertEquals(cases.size(), ids.size(), "duplicate case ids");
    }

    @Test
    void corpusCoversAllFailureCategoriesAndScenarios() throws Exception {
        List<GoldenCase> cases = GoldenCase.loadDirectory(FIXTURES);

        Set<FailureCategory> categories = cases.stream()
                .map(GoldenCase::expectedCategory)
                .collect(Collectors.toSet());
        assertTrue(categories.contains(FailureCategory.LOGIC_ERROR));
        assertTrue(categories.contains(FailureCategory.DEPENDENCY_BUILD));
        assertTrue(categories.contains(FailureCategory.INFRA_FLAKY));
        assertTrue(categories.contains(FailureCategory.UNKNOWN));

        assertTrue(cases.stream().anyMatch(GoldenCase::shouldBeUnknown), "missing unknown cases");
        assertTrue(cases.stream().anyMatch(c -> !c.requiredTools().isEmpty()),
                "missing path-sensitive cases (requiredTools)");
        assertTrue(cases.stream().anyMatch(c -> !c.forbiddenConclusions().isEmpty()),
                "missing forbidden-conclusion cases");
    }

    @Test
    void pathSensitiveCasesCoverTheIndependentInvestigationDirections() throws Exception {
        List<GoldenCase> cases = GoldenCase.loadDirectory(FIXTURES);

        Set<String> required = cases.stream()
                .flatMap(c -> c.requiredTools().stream())
                .collect(Collectors.toSet());
        assertTrue(required.contains("dependency_analysis"), "no dependency-direction case");
        assertTrue(required.contains("source_symbol_search"), "no symbol-search-direction case");
        assertTrue(required.contains("flaky_history"), "no flaky-history-direction case");
        assertTrue(required.contains("git_history"), "no git-regression-direction case");
    }

    @Test
    void runsBaselineEvalOverWholeCorpusWithoutErrors() throws Exception {
        List<GoldenCase> cases = GoldenCase.loadDirectory(FIXTURES);
        EvalReport report = new EvalRunner().runBaseline(cases, new FakeLLMProvider());

        assertEquals(cases.size(), report.results().size());
        for (EvalResult result : report.results()) {
            assertEquals("", result.error(), () -> result.caseId() + " errored: " + result.error());
            assertEquals("COMPLETED", result.stopReason(), () -> result.caseId() + " stopReason");
        }
    }
}
