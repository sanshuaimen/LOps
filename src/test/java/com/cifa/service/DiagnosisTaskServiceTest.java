package com.cifa.service;

import com.cifa.classification.FailureCategory;
import com.cifa.context.ContextBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosisTaskServiceTest {

    @Test
    void prefersSurefireReportOverConsoleLog(@TempDir Path repoRoot) throws Exception {
        Path reports = repoRoot.resolve("target/surefire-reports");
        Files.createDirectories(reports);
        Files.copy(
                Path.of("fixtures/surefire-xml/TEST-com.example.UserServiceTest.xml"),
                reports.resolve("TEST-com.example.UserServiceTest.xml"));
        Path log = repoRoot.resolve("build.log");
        Files.writeString(log, "[ERROR] a different console failure\n");

        PreparedDiagnosis prepared = prepare(log, repoRoot);

        assertTrue(prepared.usedXmlReport());
        assertEquals("java.lang.NullPointerException", prepared.classified().failure().exceptionType());
        assertEquals(prepared.classified().failure().exceptionType(), prepared.context().errorType());
    }

    @Test
    void skipsCorrelationForInfrastructureFailure(@TempDir Path repoRoot) throws Exception {
        Path log = repoRoot.resolve("build.log");
        Files.writeString(log, """
                java.net.SocketTimeoutException: Connect timed out
                \tat com.example.IntegrationTest.testExternalServiceCall(IntegrationTest.java:22)
                """);

        PreparedDiagnosis prepared = prepare(log, repoRoot);

        assertEquals(FailureCategory.INFRA_FLAKY, prepared.classified().category());
        assertFalse(prepared.correlationAttempted());
        assertNull(prepared.commit());
        assertEquals("", prepared.context().diffHunk());
    }

    @Test
    void stillAttemptsCorrelationForDependencyFailure(@TempDir Path repoRoot) throws Exception {
        Path log = repoRoot.resolve("build.log");
        Files.writeString(log, "[ERROR] Could not resolve dependencies for project com.example:app:jar:1.0.0\n");

        PreparedDiagnosis prepared = prepare(log, repoRoot);

        assertEquals(FailureCategory.DEPENDENCY_BUILD, prepared.classified().category());
        assertTrue(prepared.correlationAttempted());
    }

    @Test
    void appliesContextBudgetInsideService(@TempDir Path repoRoot) throws Exception {
        Path log = repoRoot.resolve("build.log");
        Files.writeString(log, """
                java.lang.NullPointerException: boom
                \tat com.example.One.run(One.java:1)
                \tat com.example.Two.run(Two.java:2)
                \tat com.example.Three.run(Three.java:3)
                """);
        DiagnosisTaskService service = new DiagnosisTaskService();

        PreparedDiagnosis prepared = service.prepare(new DiagnosisRequest(
                log, repoRoot, new ContextBudget(2, 10, 10)));

        assertEquals(2, prepared.context().topFrames().size());
        assertEquals("java.lang.NullPointerException", prepared.context().errorType());
    }

    private PreparedDiagnosis prepare(Path log, Path repoRoot) throws Exception {
        return new DiagnosisTaskService().prepare(new DiagnosisRequest(log, repoRoot, ContextBudget.DEFAULT));
    }
}
