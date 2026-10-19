package com.cifa.context;

import com.cifa.classification.ClassifiedFailure;
import com.cifa.classification.FailureCategory;
import com.cifa.correlation.CorrelatedCommit;
import com.cifa.parsing.ParsedFailure;
import com.cifa.parsing.StackFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextBuilderTest {

    private static StackFrame frame(int n) {
        return new StackFrame("com.example.Class" + n, "method" + n, "Class" + n + ".java", n);
    }

    @Test
    void truncatesFramesAndLogsExclusion() {
        List<StackFrame> frames = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            frames.add(frame(i));
        }
        ParsedFailure failure = new ParsedFailure("java.lang.NullPointerException", "msg", frames, List.of(), "");
        ClassifiedFailure classified = new ClassifiedFailure(failure, FailureCategory.LOGIC_ERROR);

        AnalysisContext context = ContextBuilder.build(classified, null, "short snippet");

        assertEquals(8, context.topFrames().size());
        assertTrue(context.excludedNotes().stream().anyMatch(n -> n.contains("truncated from 12 to 8")));
        assertEquals("", context.diffHunk());
    }

    @Test
    void truncatesOversizedDiffHunk() {
        ParsedFailure failure = new ParsedFailure("java.lang.NullPointerException", "msg", List.of(), List.of(), "");
        ClassifiedFailure classified = new ClassifiedFailure(failure, FailureCategory.LOGIC_ERROR);
        String bigDiff = "x".repeat(3000);
        CorrelatedCommit commit = new CorrelatedCommit("abc", "Jane", "2026-07-20", bigDiff);

        AnalysisContext context = ContextBuilder.build(classified, commit, "");

        assertEquals(2000, context.diffHunk().length());
        assertTrue(context.excludedNotes().stream().anyMatch(n -> n.contains("diff hunk truncated")));
    }

    @Test
    void truncatesOversizedMessageAndFailingTests() {
        // a multi-MB single log line captured by the exception regex must not
        // flow verbatim into prompts: message and failing tests are capped too
        ParsedFailure failure = new ParsedFailure("java.lang.OutOfMemoryError",
                "x".repeat(5000),
                List.of(),
                java.util.Collections.nCopies(60, "com.example.SomeTest.someMethod"),
                "");
        ClassifiedFailure classified = new ClassifiedFailure(failure, FailureCategory.INFRA_FLAKY);

        AnalysisContext context = ContextBuilder.build(classified, null, "");

        assertEquals(2000, context.message().length());
        assertEquals(20, context.failingTests().size());
        assertTrue(context.excludedNotes().stream().anyMatch(n -> n.contains("failure message truncated")));
        assertTrue(context.excludedNotes().stream().anyMatch(n -> n.contains("failing tests truncated from 60 to 20")));
    }

    @Test
    void readSourceSnippetReturnsWindowAroundLine(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("Sample.java");
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            content.append("line ").append(i).append('\n');
        }
        Files.writeString(file, content.toString());

        String snippet = ContextBuilder.readSourceSnippet(file, 25);

        assertTrue(snippet.contains("line 25"));
        assertFalse(snippet.contains("line 5\n"));
        assertFalse(snippet.contains("line 45\n"));
    }

    @Test
    void readSourceSnippetReturnsEmptyForMissingFile() {
        assertEquals("", ContextBuilder.readSourceSnippet(Path.of("/does/not/exist/File.java"), 5));
    }

    @Test
    void readSourceSnippetIncludesFirstLineWhenTargetLineIsNearTop(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("Sample.java");
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            content.append("line ").append(i).append('\n');
        }
        Files.writeString(file, content.toString());

        String snippet = ContextBuilder.readSourceSnippet(file, 1);

        assertTrue(snippet.contains("line 1\n"));
        assertTrue(snippet.startsWith("line 1\n"));
    }

    @Test
    void mergesExtraNotesWithTruncationNotes() {
        List<StackFrame> frames = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            frames.add(frame(i));
        }
        ParsedFailure failure = new ParsedFailure("java.lang.NullPointerException", "msg", frames, List.of(), "");
        ClassifiedFailure classified = new ClassifiedFailure(failure, FailureCategory.LOGIC_ERROR);

        AnalysisContext context = ContextBuilder.build(
                classified, null, "", List.of("source file could not be located for com.example.Missing"));

        assertTrue(context.excludedNotes().contains("source file could not be located for com.example.Missing"));
        assertTrue(context.excludedNotes().stream().anyMatch(n -> n.contains("truncated from 12 to 8")));
    }

    @Test
    void honorsCustomContextBudget() {
        ParsedFailure failure = new ParsedFailure("java.lang.NullPointerException", "msg", List.of(), List.of(), "");
        ClassifiedFailure classified = new ClassifiedFailure(failure, FailureCategory.LOGIC_ERROR);
        String bigSnippet = "x".repeat(500);
        ContextBudget tinyBudget = new ContextBudget(8, 100, 100);

        AnalysisContext context = ContextBuilder.build(classified, null, bigSnippet, List.of(), tinyBudget);

        assertEquals(100, context.sourceSnippet().length());
        assertTrue(context.excludedNotes().stream().anyMatch(n -> n.contains("truncated from 500 to 100")));
    }
}
