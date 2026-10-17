package com.cifa.context;

import com.cifa.classification.FailureCategory;
import com.cifa.parsing.StackFrame;

import java.util.List;

public record AnalysisContext(
        String errorType,
        String message,
        List<String> failingTests,
        List<StackFrame> topFrames,
        String sourceSnippet,
        String diffHunk,
        FailureCategory category,
        List<String> excludedNotes
) {
}
