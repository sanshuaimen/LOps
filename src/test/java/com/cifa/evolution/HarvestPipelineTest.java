package com.cifa.evolution;

import com.cifa.llm.LLMProvider;
import com.cifa.llm.LLMRequest;
import com.cifa.llm.LLMResponse;
import com.cifa.eval.GoldenCase;
import com.cifa.memory.FailureMemoryEntry;
import com.cifa.memory.FailureMemoryStore;
import com.cifa.memory.MemoryDistiller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reflow pipe's contract: holdout-sourced traces are blocked at the door
 * (double-blind, door one) and NOTHING from them is distilled; proposal traces
 * flow into memory + skill candidates with full provenance; skipped outcomes
 * are counted honestly.
 */
class HarvestPipelineTest {

    @TempDir
    Path temp;

    /** Scripted provider: one queued raw response per invoke, in order. */
    private static final class ScriptedProvider implements LLMProvider {
        private final Deque<String> script = new ArrayDeque<>();

        ScriptedProvider enqueue(String raw) {
            script.add(raw);
            return this;
        }

        @Override
        public LLMResponse invoke(LLMRequest request) {
            String next = script.poll();
            return new LLMResponse(next == null ? "" : next, null, 1, 1, 0);
        }

        @Override
        public com.cifa.llm.AnalysisResult analyze(com.cifa.context.AnalysisContext context) {
            throw new UnsupportedOperationException();
        }
    }

    private static final Set<String> TOOLS = Set.of("git_history", "flaky_history", "source_location");

    /** Mini fixture corpus: one case per id, each with a log carrying an exception line. */
    private List<GoldenCase> fixtures() throws IOException {
        List<GoldenCase> cases = new ArrayList<>();
        for (String id : List.of("case-a", "case-b", "case-c", "case-d")) {
            Path dir = temp.resolve("fixtures").resolve(id);
            Files.createDirectories(dir.resolve("repo"));
            Files.writeString(dir.resolve("build.log"),
                    "Tests run: 1, Failures: 1\njava.lang.NullPointerException: cart is null\n",
                    StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("case.json"), "{\"layer\":\"robustness\",\"id\":\"" + id
                    + "\",\"expectedCategory\":\"LOGIC_ERROR\"}", StandardCharsets.UTF_8);
            cases.add(GoldenCase.load(dir.resolve("case.json")));
        }
        return cases;
    }

    /** A trace JSONL in the eval taskId convention ("eval-<caseId>"). */
    private Path writeTrace(String name, String caseId) throws IOException {
        Path file = temp.resolve(name);
        String conclusion = "{\"taskId\":\"eval-" + caseId + "\",\"sequence\":1,\"timestamp\":"
                + "\"2026-11-01T00:00:00Z\",\"eventType\":\"conclusion\",\"round\":3,\"model\":\"fake\","
                + "\"promptVersion\":\"p0-v1\",\"hypothesis\":\"{\\\"category\\\":\\\"LOGIC_ERROR\\\","
                + "\\\"rootCause\\\":\\\"null cart\\\"}\",\"toolName\":\"\",\"argumentsSummary\":\"\","
                + "\"evidenceIds\":[\"SRC-1\"],\"tokenCount\":50,\"elapsedMillis\":10,"
                + "\"status\":\"completed\",\"stopReason\":\"COMPLETED\",\"error\":\"\"}";
        Files.writeString(file, conclusion, StandardCharsets.UTF_8);
        return file;
    }

    private static final String LESSON = """
            {"worth_storing":true,"category":"LOGIC_ERROR",
             "signature":"java.lang.NullPointerException: cart is null",
             "exception_type":"NullPointerException","keywords":["nullpointerexception","cart"],
             "pattern":"null cart reference in checkout flow",
             "lesson":"guard the cart reference before use","reason":"generalizable"}""";

    private static final String SKILL = """
            {"propose":true,"kind":"ROUTING","name":"null-cart-locate",
             "category":"LOGIC_ERROR","symptomKeywords":["nullpointerexception"],
             "routingTools":["source_location","git_history"],
             "reason":"NPE class needs source location first"}""";

    @Test
    void holdoutTraceIsBlockedAtTheDoorAndProposalTraceFlows() throws Exception {
        List<GoldenCase> cases = fixtures();
        CorpusPartition partition = CorpusPartition.build(cases, 0.25, "harvest-seed");
        String holdoutCase = partition.holdoutIds().iterator().next();
        String proposalCase = partition.proposalIds().iterator().next();

        // proposal trace FIRST in file order; script serves only it — a holdout
        // leak would exhaust this queue and fail loudly. (MemoryDistiller.consolidate
        // short-circuits ADD on an empty notebook: no provider call there.)
        ScriptedProvider provider = new ScriptedProvider();
        provider.enqueue(LESSON).enqueue(SKILL);

        FailureMemoryStore memory = new FailureMemoryStore(temp.resolve("memory.json"));
        SkillsStore skills = new SkillsStore(temp.resolve("skills.json"),
                new SkillAuditLog(temp.resolve("audit.jsonl")));
        RejectedPathStore rejected = new RejectedPathStore(temp.resolve("rejected.json"));

        HarvestPipeline pipeline = new HarvestPipeline(memory, skills, rejected, partition,
                new MemoryDistiller(provider, "fake"),
                new SkillProposer(provider, "fake", TOOLS, ProposalBudget.defaults()),
                2, caseId -> "java.lang.NullPointerException: cart is null");

        List<Path> traces = List.of(
                writeTrace("trace-a-" + proposalCase + ".jsonl", proposalCase),
                writeTrace("trace-b-" + holdoutCase + ".jsonl", holdoutCase));

        HarvestPipeline.Report report = pipeline.run(traces);

        assertEquals(2, report.tracesRead());
        assertEquals(1, report.holdoutBlocked(), "the holdout trace must be counted as blocked");
        assertEquals(1, report.memoryAdded());
        assertEquals(1, report.skillsProposed());

        // what landed carries provenance from the PROPOSAL case only
        FailureMemoryEntry stored = memory.entries().get(0);
        assertEquals(proposalCase, stored.caseId());
        assertTrue(skills.all().get(0).sourceTrace().contains(proposalCase),
                "the skill's provenance names its trace file");
        assertEquals(0, rejected.size());
    }

    @Test
    void skippedOutcomesAreCountedNotSilent() throws Exception {
        List<GoldenCase> cases = fixtures();
        CorpusPartition partition = CorpusPartition.build(cases, 0.25, "harvest-seed");
        String proposalCase = partition.proposalIds().iterator().next();

        ScriptedProvider provider = new ScriptedProvider();
        provider.enqueue("{\"worth_storing\":false,\"reason\":\"case-specific typo\"}")
                .enqueue("{\"propose\":false,\"reason\":\"nothing generalizable\"}");

        HarvestPipeline pipeline = new HarvestPipeline(
                new FailureMemoryStore(temp.resolve("m.json")),
                new SkillsStore(temp.resolve("s.json"), new SkillAuditLog(temp.resolve("a.jsonl"))),
                null, partition, new MemoryDistiller(provider, "fake"),
                new SkillProposer(provider, "fake", TOOLS, ProposalBudget.defaults()),
                2, caseId -> null);

        HarvestPipeline.Report report = pipeline.run(
                List.of(writeTrace("trace-x.jsonl", proposalCase)));

        assertEquals(1, report.memorySkipped());
        assertEquals(1, report.skillsSkipped());
        assertEquals(0, report.memoryAdded() + report.skillsProposed());
    }

    @Test
    void parseExtractsCaseIdVerdictAndText() throws Exception {
        Path trace = writeTrace("parse-check.jsonl", "case-a");
        HarvestPipeline.ParsedTrace parsed = HarvestPipeline.parse(trace);
        assertEquals("eval-case-a", parsed.taskId());
        assertEquals("case-a", parsed.caseId());
        assertTrue(parsed.traceText().contains("[conclusion]"));
        assertTrue(parsed.verdictSummary().contains("COMPLETED"));
        assertTrue(parsed.verdictSummary().contains("LOGIC_ERROR"));
    }
}
