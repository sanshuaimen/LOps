package com.cifa.context;

import com.cifa.classification.ClassifiedFailure;
import com.cifa.correlation.CorrelatedCommit;
import com.cifa.parsing.StackFrame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ContextBuilder {

    /** The failure message flows into every prompt verbatim; a 2MB single log line
     * matched by the exception regex must not become a 2MB prompt section. */
    private static final int MAX_MESSAGE_CHARS = 2000;
    private static final int MAX_FAILING_TESTS = 20;

    private ContextBuilder() {
    }

    public static AnalysisContext build(ClassifiedFailure classified, CorrelatedCommit commit, String sourceSnippet) {
        return build(classified, commit, sourceSnippet, List.of());
    }

    public static AnalysisContext build(ClassifiedFailure classified, CorrelatedCommit commit, String sourceSnippet,
                                         List<String> extraNotes) {
        return build(classified, commit, sourceSnippet, extraNotes, ContextBudget.DEFAULT);
    }

    public static AnalysisContext build(ClassifiedFailure classified, CorrelatedCommit commit, String sourceSnippet,
                                         List<String> extraNotes, ContextBudget budget) {
        List<String> excluded = new ArrayList<>(extraNotes);
        List<StackFrame> frames = classified.failure().frames();

        List<StackFrame> topFrames = frames;
        if (frames.size() > budget.maxFrames()) {
            topFrames = frames.subList(0, budget.maxFrames());
            excluded.add("stack trace truncated from " + frames.size() + " to " + budget.maxFrames() + " frames");
        }

        String snippet = sourceSnippet == null ? "" : sourceSnippet;
        if (snippet.length() > budget.maxSnippetChars()) {
            excluded.add("source snippet truncated from " + snippet.length() + " to " + budget.maxSnippetChars() + " chars");
            snippet = snippet.substring(0, budget.maxSnippetChars());
        }

        String diffHunk = commit == null ? "" : commit.diffHunk();
        if (diffHunk.length() > budget.maxDiffChars()) {
            excluded.add("diff hunk truncated from " + diffHunk.length() + " to " + budget.maxDiffChars() + " chars");
            diffHunk = diffHunk.substring(0, budget.maxDiffChars());
        }

        String message = classified.failure().message();
        if (message.length() > MAX_MESSAGE_CHARS) {
            excluded.add("failure message truncated from " + message.length() + " to " + MAX_MESSAGE_CHARS + " chars");
            message = message.substring(0, MAX_MESSAGE_CHARS);
        }

        List<String> failingTests = classified.failure().failingTests();
        if (failingTests.size() > MAX_FAILING_TESTS) {
            excluded.add("failing tests truncated from " + failingTests.size() + " to " + MAX_FAILING_TESTS);
            failingTests = failingTests.subList(0, MAX_FAILING_TESTS);
        }

        return new AnalysisContext(
                classified.failure().exceptionType(),
                message,
                failingTests,
                topFrames,
                snippet,
                diffHunk,
                classified.category(),
                excluded
        );
    }

    public static String readSourceSnippet(Path filePath, int lineNumber) {
        try {
            if (!Files.exists(filePath)) {
                return "";
            }
            List<String> lines = Files.readAllLines(filePath);
            int start = Math.max(0, lineNumber - 16);
            int end = Math.min(lines.size(), lineNumber + 15);
            return String.join("\n", lines.subList(start, end));
        } catch (IOException e) {
            return "";
        }
    }
}
