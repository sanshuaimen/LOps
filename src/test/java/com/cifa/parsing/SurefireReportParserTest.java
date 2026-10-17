package com.cifa.parsing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SurefireReportParserTest {

    private static final String EQUIVALENT_CONSOLE_STACK = """
            java.lang.NullPointerException: Cannot invoke "com.example.CustomerType.getDiscountRate()" because the return value of "com.example.Customer.getType()" is null
            \tat com.example.UserService.calculateDiscount(UserService.java:9)
            \tat com.example.UserServiceTest.testPremiumDiscount(UserServiceTest.java:7)
            """;

    private static Path installFixtureReport(Path repoRoot) throws Exception {
        Path reportsDir = repoRoot.resolve("target/surefire-reports");
        Files.createDirectories(reportsDir);
        Path dest = reportsDir.resolve("TEST-com.example.UserServiceTest.xml");
        Files.copy(Path.of("fixtures/surefire-xml/TEST-com.example.UserServiceTest.xml"), dest);
        return dest;
    }

    @Test
    void extractsSameStructuredDataAsConsoleLogPath(@TempDir Path repoRoot) throws Exception {
        installFixtureReport(repoRoot);

        ParsedFailure fromXml = SurefireReportParser.parse(repoRoot);
        ParsedFailure fromConsole = FailureLogParser.parse(EQUIVALENT_CONSOLE_STACK);

        assertEquals(fromConsole.exceptionType(), fromXml.exceptionType());
        assertEquals(fromConsole.message(), fromXml.message());
        assertEquals(fromConsole.frames(), fromXml.frames());
        assertEquals(List.of("UserServiceTest.testPremiumDiscount"), fromXml.failingTests());
    }

    @Test
    void returnsNullWhenNoReportsPresent(@TempDir Path repoRoot) {
        assertNull(SurefireReportParser.parse(repoRoot));
    }
}
