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

        return new AnalysisContext(
                classified.failure().exceptionType(),
                classified.failure().message(),
                classified.failure().failingTests(),
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
