package com.cifa.memory;

import com.cifa.context.AnalysisContext;
import com.cifa.llm.AnalysisResult;
import com.cifa.llm.FakeLLMProvider;
import com.cifa.llm.LLMProvider;
import com.cifa.llm.LLMRequest;
import com.cifa.llm.LLMResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class MemoryDistillerTest {

    /** Distiller responses are raw model text, NOT agent decisions — served
     * verbatim so scripts never pass through the decision parser (same idiom
     * as the judge tests). */
    static class RawProvider implements LLMProvider {
        private final Queue<Object> script = new ArrayDeque<>();
        final List<LLMRequest> requests = new ArrayList<>();

        RawProvider enqueue(String rawContent) {
            script.add(rawContent);
            return this;
        }

        RawProvider enqueueFailure(Exception failure) {
            script.add(failure);
            return this;
        }

        @Override
        public LLMResponse invoke(LLMRequest request) throws IOException, InterruptedException {
            requests.add(request);
            Object next = script.poll();
            if (next instanceof IOException ioException) {
                throw ioException;
            }
            if (next instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            String content = next == null ? "" : next.toString();
            return new LLMResponse(content, null, 1, 1, 0);
        }

        @Override
        public AnalysisResult analyze(AnalysisContext context) {
            throw new UnsupportedOperationException("distiller never uses the baseline path");
        }
    }

    private static final String LESSON_JSON = """
            {"worth_storing":true,"category":"LOGIC_ERROR",
             "signature":"java.lang.NullPointerException: cannot invoke Foo.bar()",
             "exception_type":"java.lang.NullPointerException",
             "keywords":["nullpointerexception","surefire","assert"],
             "pattern":"NPE from an unguarded accessor chain inside a test assertion helper",
             "lesson":"Guard accessor chains behind Objects.requireNonNull with a domain message before asserting; inspect the failing frame's class for the unchecked lookup.",
             "reason":"teaches the guard pattern"}
            """;

    private static FailureMemoryEntry entry(String id, String caseId, String signature, String lesson) {
        return new FailureMemoryEntry(id, caseId, "distilled", signature, "",
                List.of("keyword"), "LOGIC_ERROR", "pattern", List.of(), lesson, "distilled:test");
    }

    // ---- step 1: distill ----------------------------------------------------

    @Test
    void distillBuildsGatedCandidate() throws InterruptedException {
        RawProvider raw = new RawProvider().enqueue(LESSON_JSON);
        MemoryDistiller.Distillation distillation =
                new MemoryDistiller(raw, "test").distill("round trace text", "task-1", null);

        FailureMemoryEntry candidate = distillation.candidate();
        assertNotNull(candidate);
        assertEquals("distilled", candidate.layer());
        assertEquals("task-1", candidate.caseId());
        assertEquals("LOGIC_ERROR", candidate.category());
        assertEquals("java.lang.NullPointerException: cannot invoke Foo.bar()", candidate.logSignature());
        assertEquals(List.of("nullpointerexception", "surefire", "assert"), candidate.keywords());
        assertTrue(candidate.remediation().contains("requireNonNull"));
        assertTrue(candidate.source().startsWith("distilled:memory-distill-v1"));
        assertTrue(raw.requests.get(0).userPrompt().contains("round trace text"));
    }

    @Test
    void deterministicSignatureOverridesModelProposal() throws InterruptedException {
        RawProvider raw = new RawProvider().enqueue(LESSON_JSON);
        MemoryDistiller.Distillation distillation = new MemoryDistiller(raw, "test").distill(
                "trace", "task-1", "Foo.java:41: error: cannot find symbol");
        assertEquals("Foo.java:41: error: cannot find symbol", distillation.candidate().logSignature());
    }

    @Test
    void skipWhenModelSaysNotWorthStoring() throws InterruptedException {
        RawProvider raw = new RawProvider().enqueue(
                "{\"worth_storing\":false,\"reason\":\"routine flake, nothing generalizable\"}");
        MemoryDistiller.Distillation distillation =
                new MemoryDistiller(raw, "test").distill("trace", "task-1", null);
        assertNull(distillation.candidate());
        assertTrue(distillation.skipReason().contains("routine flake"));
    }

    @Test
    void skipOnGarbageOutput() throws InterruptedException {
        RawProvider raw = new RawProvider().enqueue("I cannot answer that in JSON");
        MemoryDistiller.Distillation distillation =
                new MemoryDistiller(raw, "test").distill("trace", "task-1", null);
        assertNull(distillation.candidate());
        assertTrue(distillation.skipReason().contains("unparseable"));
    }

    @Test
    void skipOnTransportFailure() throws InterruptedException {
        RawProvider raw = new RawProvider().enqueueFailure(new IOException("connection reset"));
        MemoryDistiller.Distillation distillation =
                new MemoryDistiller(raw, "test").distill("trace", "task-1", null);
        assertNull(distillation.candidate());
        assertTrue(distillation.skipReason().contains("distill call failed"));
    }

    @Test
    void gateRejectsBlankLesson() throws InterruptedException {
        RawProvider raw = new RawProvider().enqueue(
                "{\"worth_storing\":true,\"category\":\"LOGIC_ERROR\","
                        + "\"signature\":\"sig\",\"pattern\":\"p\",\"lesson\":\"\"}");
        MemoryDistiller.Distillation distillation =
                new MemoryDistiller(raw, "test").distill("trace", "task-1", null);
        assertNull(distillation.candidate());
        assertTrue(distillation.skipReason().contains("rejected by gate"));
    }

    @Test
    void unknownCategoryNormalizesToUnknown() throws InterruptedException {
        RawProvider raw = new RawProvider().enqueue(LESSON_JSON.replace("LOGIC_ERROR", "SOMETHING_ELSE"));
        MemoryDistiller.Distillation distillation =
                new MemoryDistiller(raw, "test").distill("trace", "task-1", null);
        assertEquals("UNKNOWN", distillation.candidate().category());
    }

    @Test
    void entryIdIsStablePerContent() throws InterruptedException {
        RawProvider first = new RawProvider().enqueue(LESSON_JSON);
        RawProvider second = new RawProvider().enqueue(LESSON_JSON);
        String id1 = new MemoryDistiller(first, "test").distill("t", "task-1", null).candidate().id();
        String id2 = new MemoryDistiller(second, "test").distill("t", "task-2", null).candidate().id();
        assertEquals(id1, id2);

        RawProvider third = new RawProvider().enqueue(
                LESSON_JSON.replace("Guard accessor chains behind",
                        "Guard every accessor chain behind"));
        String id3 = new MemoryDistiller(third, "test").distill("t", "task-1", null).candidate().id();
        assertNotEquals(id1, id3);
    }

    // ---- step 2: consolidate -------------------------------------------------

    @Test
    void noSimilarEntriesShortCircuitsToAddWithoutLLMCall() throws InterruptedException {
        RawProvider raw = new RawProvider();
        FailureMemoryEntry candidate = entry("dist-aa", "task-1", "sig", "lesson");
        MemoryDistiller.Consolidation consolidation =
                new MemoryDistiller(raw, "test").consolidate(candidate, List.of());
        assertEquals(MemoryDistiller.Action.ADD, consolidation.action());
        assertEquals(candidate, consolidation.entry());
        assertTrue(raw.requests.isEmpty());
    }

    @Test
    void modelAddApprovalPassesCandidateThrough() throws InterruptedException {
        RawProvider raw = new RawProvider().enqueue("{\"decision\":\"add\",\"reason\":\"new lesson\"}");
        FailureMemoryEntry candidate = entry("dist-aa", "task-1", "sig", "lesson");
        MemoryDistiller.Consolidation consolidation = new MemoryDistiller(raw, "test")
                .consolidate(candidate, List.of(entry("dist-bb", "task-9", "other sig", "other lesson")));
        assertEquals(MemoryDistiller.Action.ADD, consolidation.action());
        assertEquals(candidate, consolidation.entry());
    }

    @Test
    void modelNoopLeavesNotebookAlone() throws InterruptedException {
        RawProvider raw = new RawProvider().enqueue(
                "{\"decision\":\"noop\",\"reason\":\"dist-bb already teaches this\"}");
        MemoryDistiller.Consolidation consolidation = new MemoryDistiller(raw, "test")
                .consolidate(entry("dist-aa", "task-1", "sig", "lesson"),
                        List.of(entry("dist-bb", "task-9", "other", "other")));
        assertEquals(MemoryDistiller.Action.NOOP, consolidation.action());
        assertNull(consolidation.entry());
    }

    @Test
    void updateMergesIntoTargetPreservingIdentity() throws InterruptedException {
        RawProvider raw = new RawProvider().enqueue("""
                {"decision":"update","target_id":"dist-bb","reason":"extends the guard lesson",
                 "merged":{"category":"LOGIC_ERROR",
                           "signature":"java.lang.NullPointerException: cannot invoke Foo.bar()",
                           "exception_type":"java.lang.NullPointerException",
                           "keywords":["nullpointerexception"],
                           "pattern":"NPE from unguarded accessor chains",
                           "lesson":"Guard accessor chains and also cover Optional misuse."}}
                """);
        FailureMemoryEntry target = entry("dist-bb", "task-9", "old sig", "old lesson");
        MemoryDistiller.Consolidation consolidation = new MemoryDistiller(raw, "test")
                .consolidate(entry("dist-aa", "task-1", "sig", "lesson"), List.of(target));

        assertEquals(MemoryDistiller.Action.UPDATE, consolidation.action());
        FailureMemoryEntry merged = consolidation.entry();
        assertEquals("dist-bb", merged.id());
        assertEquals("task-9", merged.caseId());
        assertEquals("distilled", merged.layer());
        assertEquals("Guard accessor chains and also cover Optional misuse.", merged.remediation());
        assertTrue(merged.source().endsWith("+memory-distill-v1"));
    }

    @Test
    void updateAgainstUnknownIdDowngradesToNoop() throws InterruptedException {
        RawProvider raw = new RawProvider().enqueue(
                "{\"decision\":\"update\",\"target_id\":\"not-shown\",\"reason\":\"...\","
                        + "\"merged\":{\"signature\":\"s\",\"pattern\":\"p\",\"lesson\":\"l\"}}");
        MemoryDistiller.Consolidation consolidation = new MemoryDistiller(raw, "test")
                .consolidate(entry("dist-aa", "task-1", "sig", "lesson"),
                        List.of(entry("dist-bb", "task-9", "other", "other")));
        assertEquals(MemoryDistiller.Action.NOOP, consolidation.action());
        assertTrue(consolidation.reason().contains("downgraded"));
    }

    @Test
    void garbageConsolidationIsNoop() throws InterruptedException {
        RawProvider raw = new RawProvider().enqueue("not json at all");
        MemoryDistiller.Consolidation consolidation = new MemoryDistiller(raw, "test")
                .consolidate(entry("dist-aa", "task-1", "sig", "lesson"),
                        List.of(entry("dist-bb", "task-9", "other", "other")));
        assertEquals(MemoryDistiller.Action.NOOP, consolidation.action());
    }

    // ---- store integration ----------------------------------------------------

    @Test
    void storeAcceptsDistilledEntriesAndUpdatesInPlace(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("memory.json");
        FailureMemoryStore store = new FailureMemoryStore(file);

        FailureMemoryEntry distilled = entry("dist-aa", "task-1", "sig", "lesson v1");
        store.append(distilled);
        assertEquals(1, store.size());

        store.update(entry("dist-aa", "task-1", "sig", "lesson v2 (merged)"));
        assertEquals(1, store.size());
        assertEquals("lesson v2 (merged)",
                new FailureMemoryStore(file).byId("dist-aa").orElseThrow().remediation());

        assertThrows(IllegalArgumentException.class,
                () -> store.update(entry("dist-missing", "task-1", "sig", "lesson")));

        FailureMemoryEntry exam = new FailureMemoryEntry("m-1", "case-1", "accept", "sig", "",
                List.of(), "LOGIC_ERROR", "p", List.of(), "l", "golden-confirmed");
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> store.append(exam));
        assertTrue(rejected.getMessage().contains("accept"));
    }

    @Test
    void fakeProviderScriptServesBothStepsFromOneFile(@TempDir Path temp) throws Exception {
        // recorded() items travel verbatim: one script drives the distill call,
        // the empty notebook short-circuits consolidation before the second call.
        Path script = temp.resolve("script.json");
        java.nio.file.Files.writeString(script, "[" + LESSON_JSON + "]");
        FakeLLMProvider provider = FakeLLMProvider.recorded(script);
        MemoryDistiller.Distillation distillation =
                new MemoryDistiller(provider, "fake").distill("trace", "task-1", null);
        assertNotNull(distillation.candidate());
        assertEquals("LOGIC_ERROR", distillation.candidate().category());
    }
}
