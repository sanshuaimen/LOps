package com.cifa.llm;

import com.cifa.context.AnalysisContext;
import com.cifa.parsing.StackFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EvidenceValidator {

    // ponytail: verification is "any 4+ char token overlaps the context" — a coarse
    // heuristic that can under-flag. Upgrade path if false negatives show up in
    // practice: require overlap across multiple distinct tokens, not just one.
    private EvidenceValidator() {
    }

    public static List<String> findUnverified(AnalysisResult result, AnalysisContext context) {
        String haystack = buildHaystack(context).toLowerCase(Locale.ROOT);
        List<String> unverified = new ArrayList<>();
        for (String claim : result.evidence()) {
            if (!containsClaim(haystack, claim)) {
                unverified.add(claim);
            }
        }
        return unverified;
    }

    // Note: MarkdownRenderer flags evidence via unverifiedEvidence.contains(item) with no
    // positional awareness — this is safe only because containsClaim is a pure function of
    // the claim string, so identical evidence strings always get the same verdict.
    private static boolean containsClaim(String haystack, String claim) {
        for (String token : claim.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (token.length() >= 4 && haystack.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String buildHaystack(AnalysisContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(context.errorType()).append(' ').append(context.message()).append(' ');
        for (String test : context.failingTests()) {
            sb.append(test).append(' ');
        }
        for (StackFrame frame : context.topFrames()) {
            sb.append(frame.className()).append(' ').append(frame.methodName()).append(' ')
                    .append(frame.fileName()).append(' ').append(frame.lineNumber()).append(' ');
        }
        sb.append(context.sourceSnippet()).append(' ').append(context.diffHunk());
        return sb.toString();
    }
}
