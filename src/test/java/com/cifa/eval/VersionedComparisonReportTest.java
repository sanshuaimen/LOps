package com.cifa.eval;

import com.cifa.llm.FakeLLMProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 6 reproducibility contract: under fake providers two runs render
 * byte-identical reports except the generated timestamp and run id (explicitly
 * normalized), and every report traces back to a corpus version and code version.
 */
class VersionedComparisonReportTest {

    @Test
    void identicalFakeRunsRenderByteIdenticalReports() throws Exception {
        List<GoldenCase> cases = GoldenCase.loadDirectory(Path.of("fixtures/eval"));
        EvalRunner runner = new EvalRunner();

        EvalReport baseline1 = runner.runBaseline(cases, new FakeLLMProvider());
        EvalReport agent1 = runner.runAgent(cases, c -> new FakeLLMProvider());
        EvalReport baseline2 = runner.runBaseline(cases, new FakeLLMProvider());
        EvalReport agent2 = runner.runAgent(cases, c -> new FakeLLMProvider());

        ReportMetadata metadata = new ReportMetadata(
                DatasetVersion.of(cases), "testsha", "fake", "run-1", Instant.EPOCH);
        String one = new ComparisonReport(baseline1, agent1, metadata).renderMarkdown();
        String two = new ComparisonReport(baseline2, agent2, metadata).renderMarkdown();

        assertEquals(one, two, "identical fake runs must render byte-identical reports");
    }

    @Test
    void differingTimestampAndRunIdNormalizeToIdenticalReports() throws Exception {
        List<GoldenCase> cases = GoldenCase.loadDirectory(Path.of("fixtures/eval"));
        EvalRunner runner = new EvalRunner();
        EvalReport baseline = runner.runBaseline(cases, new FakeLLMProvider());
        EvalReport agent = runner.runAgent(cases, c -> new FakeLLMProvider());

        String early = new ComparisonReport(baseline, agent, new ReportMetadata(
                DatasetVersion.of(cases), "testsha", "fake", "aaa", Instant.EPOCH)).renderMarkdown();
        String late = new ComparisonReport(baseline, agent, new ReportMetadata(
                DatasetVersion.of(cases), "testsha", "fake", "zzz",
                Instant.EPOCH.plusSeconds(12345))).renderMarkdown();

        assertEquals(normalize(early), normalize(late),
                "only generated/run id may differ between two fake runs");
        assertNotEquals(early, late, "sanity: the two fields really do render");
    }

    @Test
    void headerTracesBackToCorpusAndCodeVersion() throws Exception {
        List<GoldenCase> cases = GoldenCase.loadDirectory(Path.of("fixtures/eval"));
        EvalReport baseline = new EvalRunner().runBaseline(cases, new FakeLLMProvider());
        EvalReport agent = new EvalRunner().runAgent(cases, c -> new FakeLLMProvider());

        ReportMetadata metadata = ReportMetadata.forCorpus(cases, "fake-scripted");
        ComparisonReport report = new ComparisonReport(baseline, agent, metadata);
        String markdown = report.renderMarkdown();

        assertTrue(markdown.contains("dataset version: " + DatasetVersion.of(cases)));
        assertTrue(markdown.contains("code version: " + CodeVersion.shortSha()));
        assertTrue(markdown.contains("provider: fake-scripted"));
        assertTrue(markdown.contains("run id: " + metadata.runId()));

        Path written = report.writeTo(Path.of("target/eval-reports"));
        assertEquals(metadata.fileName(), written.getFileName().toString());
        assertTrue(written.getFileName().toString()
                .matches("comparison-[0-9a-f]+-fake-scripted-[0-9a-f]+\\.md"));
        assertEquals(markdown, Files.readString(written));
    }

    @Test
    void payoffSectionReportsNegativeConclusionsExplicitly() throws Exception {
        List<GoldenCase> cases = GoldenCase.loadDirectory(Path.of("fixtures/eval"));
        EvalRunner runner = new EvalRunner();
        EvalReport baseline = runner.runBaseline(cases, new FakeLLMProvider());
        EvalReport agent = runner.runAgent(cases, VersionedComparisonReportTest::providerFor);

        String markdown = new ComparisonReport(baseline, agent, ReportMetadata.adhoc()).renderMarkdown();

        assertTrue(markdown.contains("## Where the agent pays off — and where it does not"));
        // Every corpus category must appear as a grouped row.
        assertTrue(markdown.contains("| LOGIC_ERROR |"));
        assertTrue(markdown.contains("| DEPENDENCY_BUILD |"));
        assertTrue(markdown.contains("| INFRA_FLAKY |"));
        assertTrue(markdown.contains("| UNKNOWN |"));
        // The fake corpus saturates accuracy on both paths: at least one explicit
        // negative verdict is mandatory (§六.4 — the agent is not presumed better).
        assertTrue(markdown.contains("no measured accuracy benefit")
                        || markdown.contains("no accuracy delta, no token cost")
                        || markdown.contains("no benefit: baseline scored higher here"),
                "explicit negative conclusion missing");
        assertTrue(markdown.contains("No measured agent benefit in:")
                || markdown.contains("pays off"),
                "verdict summary missing");
    }

    /** Recorded agent script when the case ships one, honest UNKNOWN otherwise. */
    private static com.cifa.llm.LLMProvider providerFor(GoldenCase goldenCase) {
        Path script = goldenCase.logPath().getParent().resolve("agent-script.json");
        if (Files.exists(script)) {
            try {
                return FakeLLMProvider.recorded(script);
            } catch (Exception e) {
                throw new IllegalStateException("bad script for " + goldenCase.id(), e);
            }
        }
        return new FakeLLMProvider();
    }

    private static String normalize(String markdown) {
        return markdown
                .replaceAll("(?m)^generated: .*$", "generated: <normalized>")
                .replaceAll("(?m)^run id: .*$", "run id: <normalized>");
    }
}
