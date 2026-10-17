package com.cifa.classification;

import com.cifa.parsing.ParsedFailure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FailureClassifierTest {

    private static ParsedFailure failure(String exceptionType, String message) {
        return new ParsedFailure(exceptionType, message, List.of(), List.of(), "");
    }

    @Test
    void classifiesNullPointerAsLogicError() {
        var result = FailureClassifier.classify(failure("java.lang.NullPointerException", "Cannot invoke x"));
        assertEquals(FailureCategory.LOGIC_ERROR, result.category());
    }

    @Test
    void classifiesSocketTimeoutAsInfraFlaky() {
        var result = FailureClassifier.classify(failure("java.net.SocketTimeoutException", "Connect timed out"));
        assertEquals(FailureCategory.INFRA_FLAKY, result.category());
    }

    @Test
    void classifiesConnectionRefusedMessageAsInfraFlaky() {
        var result = FailureClassifier.classify(failure("java.io.IOException", "Connection refused"));
        assertEquals(FailureCategory.INFRA_FLAKY, result.category());
    }

    @Test
    void classifiesDependencyResolutionAsDependencyBuild() {
        var result = FailureClassifier.classify(failure("UNKNOWN", "Could not resolve dependencies for project"));
        assertEquals(FailureCategory.DEPENDENCY_BUILD, result.category());
    }

    @Test
    void classifiesUnrecognizedAsUnknown() {
        var result = FailureClassifier.classify(failure("UNKNOWN", "Something odd happened"));
        assertEquals(FailureCategory.UNKNOWN, result.category());
    }

    @Test
    void classifiesDiskExhaustionAsInfraFlaky() {
        var result = FailureClassifier.classify(failure("java.io.IOException", "No space left on device"));
        assertEquals(FailureCategory.INFRA_FLAKY, result.category());
    }

    @Test
    void classifiesRunnerDisconnectAsInfraFlaky() {
        var result = FailureClassifier.classify(failure("java.io.IOException", "runner has lost communication with the server"));
        assertEquals(FailureCategory.INFRA_FLAKY, result.category());
    }
}
