package com.cifa.classification;

import com.cifa.parsing.ParsedFailure;

import java.util.List;
import java.util.Locale;

public final class FailureClassifier {

    private static final List<String> INFRA_KEYWORDS = List.of(
            "outofmemoryerror", "sockettimeoutexception", "connectexception",
            "unknownhostexception", "timeoutexception", "connection refused",
            "timed out", "could not connect", "no route to host",
            "no space left on device", "too many open files",
            "lost communication with the server", "temporary failure in name resolution",
            "name or service not known"
    );

    private static final List<String> DEPENDENCY_KEYWORDS = List.of(
            "could not resolve dependencies", "could not find artifact",
            "non-resolvable parent pom", "compilation failure",
            "cannot find symbol", "package does not exist",
            "dependencyresolutionexception", "mojoexecutionexception"
    );

    private FailureClassifier() {
    }

    public static ClassifiedFailure classify(ParsedFailure failure) {
        String haystack = (failure.exceptionType() + " " + failure.message()).toLowerCase(Locale.ROOT);

        if (containsAny(haystack, INFRA_KEYWORDS)) {
            return new ClassifiedFailure(failure, FailureCategory.INFRA_FLAKY);
        }
        if (containsAny(haystack, DEPENDENCY_KEYWORDS)) {
            return new ClassifiedFailure(failure, FailureCategory.DEPENDENCY_BUILD);
        }
        String exceptionType = failure.exceptionType().toLowerCase(Locale.ROOT);
        if (exceptionType.endsWith("exception") || exceptionType.endsWith("error")) {
            return new ClassifiedFailure(failure, FailureCategory.LOGIC_ERROR);
        }
        return new ClassifiedFailure(failure, FailureCategory.UNKNOWN);
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
