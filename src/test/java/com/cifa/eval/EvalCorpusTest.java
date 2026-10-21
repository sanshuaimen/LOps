package com.cifa.eval;

import com.cifa.classification.FailureCategory;
import com.cifa.llm.FakeLLMProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
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

        // Per-category floors lock in the P0-14 batch-2 distribution (33 cases):
        // a later batch must not quietly delete a scenario family.
        Map<FailureCategory, Long> counts = cases.stream()
                .collect(Collectors.groupingBy(GoldenCase::expectedCategory, Collectors.counting()));
        assertTrue(counts.getOrDefault(FailureCategory.LOGIC_ERROR, 0L) >= 10,
                "LOGIC_ERROR floor: " + counts);
        assertTrue(counts.getOrDefault(FailureCategory.DEPENDENCY_BUILD, 0L) >= 10,
                "DEPENDENCY_BUILD floor: " + counts);
        assertTrue(counts.getOrDefault(FailureCategory.INFRA_FLAKY, 0L) >= 5,
                "INFRA_FLAKY floor: " + counts);
        assertTrue(counts.getOrDefault(FailureCategory.UNKNOWN, 0L) >= 4,
                "UNKNOWN floor: " + counts);
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
    void corpusCarriesPerLayerFloors() throws Exception {
        List<GoldenCase> cases = GoldenCase.loadDirectory(FIXTURES);

        Map<String, Long> counts = cases.stream()
                .collect(Collectors.groupingBy(GoldenCase::layer, Collectors.counting()));
        // Floors lock the 5c expansion of the corpus (62 cases); a later batch must
        // not quietly hollow out a layer (accept = path-sensitive acceptance set).
        assertTrue(counts.getOrDefault("accept", 0L) >= 20, "accept floor: " + counts);
        assertTrue(counts.getOrDefault("regression", 0L) >= 19, "regression floor: " + counts);
        assertTrue(counts.getOrDefault("robustness", 0L) >= 21, "robustness floor: " + counts);
        assertTrue(counts.getOrDefault("security", 0L) >= 2, "security floor: " + counts);
        assertEquals(Set.copyOf(GoldenCase.knownLayers()), counts.keySet(),
                "every known layer must be present; counts=" + counts);
    }

    @Test
    void loadDirectoryByLayerFiltersAndRejectsUnknownLayers() throws Exception {
        List<GoldenCase> accept = GoldenCase.loadDirectory(FIXTURES, "accept");
        assertFalse(accept.isEmpty());
        assertTrue(accept.stream().allMatch(c -> c.layer().equals("accept")));
        assertTrue(accept.size() < GoldenCase.loadDirectory(FIXTURES).size(), "layer filter must narrow");

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> GoldenCase.loadDirectory(FIXTURES, "bogus"));
        assertTrue(unknown.getMessage().contains("bogus"));
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
