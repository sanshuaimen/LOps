package com.cifa.parsing;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FailureLogParserTest {

    private static final String JAVA_LOG = """
            [INFO] Running com.example.UserServiceTest
            [ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.045 s <<< FAILURE! - in com.example.UserServiceTest
            [ERROR] testPremiumDiscount  Time elapsed: 0.012 s  <<< ERROR!
            java.lang.NullPointerException: Cannot invoke "com.example.CustomerType.getDiscountRate()" because the return value of "com.example.Customer.getType()" is null
            \tat com.example.UserService.calculateDiscount(UserService.java:9)
            \tat com.example.UserServiceTest.testPremiumDiscount(UserServiceTest.java:7)
            \tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
            \tat org.junit.platform.commons.util.ReflectionUtils.invokeMethod(ReflectionUtils.java:728)
            [INFO] BUILD FAILURE
            """;

    @Test
    void parsesJavaStackTraceExceptionTypeAndFailingTest() {
        ParsedFailure result = FailureLogParser.parse(JAVA_LOG);

        assertEquals("java.lang.NullPointerException", result.exceptionType());
        assertTrue(result.message().contains("Customer.getType()"));
        assertEquals(List.of("UserServiceTest.testPremiumDiscount"), result.failingTests());

        assertEquals(4, result.frames().size());
        StackFrame first = result.frames().get(0);
        assertEquals("com.example.UserService", first.className());
        assertEquals("calculateDiscount", first.methodName());
        assertEquals("UserService.java", first.fileName());
        assertEquals(9, first.lineNumber());

        StackFrame nativeFrame = result.frames().get(2);
        assertEquals("Native Method", nativeFrame.fileName());
        assertEquals(0, nativeFrame.lineNumber());
    }

    @Test
    void fallsBackToGenericWhenNoJavaExceptionFound() {
        ParsedFailure result = FailureLogParser.parse("Something happened\nFAILED: smoke test\nmore noise\n");

        assertEquals("UNKNOWN", result.exceptionType());
        assertEquals("FAILED: smoke test", result.message());
        assertTrue(result.frames().isEmpty());
    }

    @Test
    void parsesPythonTraceback() {
        String log = """
                Traceback (most recent call last):
                  File "app/service.py", line 42, in calculate
                    return customer.type.rate
                AttributeError: 'NoneType' object has no attribute 'rate'
                """;

        ParsedFailure result = FailureLogParser.parse(log);

        assertEquals("AttributeError", result.exceptionType());
        assertTrue(result.message().contains("NoneType"));
        assertEquals(1, result.frames().size());
        assertEquals("service.py", result.frames().get(0).fileName());
        assertEquals(42, result.frames().get(0).lineNumber());
    }

    @Test
    void parsesNodeStackTrace() {
        String log = """
                TypeError: Cannot read properties of undefined (reading 'rate')
                    at calculateDiscount (/app/service.js:15:22)
                    at Object.<anonymous> (/app/test.js:8:5)
                """;

        ParsedFailure result = FailureLogParser.parse(log);

        assertEquals("TypeError", result.exceptionType());
        assertTrue(result.message().contains("Cannot read properties"));
        assertEquals(2, result.frames().size());
        assertEquals("/app/service.js", result.frames().get(0).fileName());
        assertEquals(15, result.frames().get(0).lineNumber());
    }

    @Test
    void prefersXmlReportsOverConsoleLogWhenBothPresent(@org.junit.jupiter.api.io.TempDir java.nio.file.Path repoRoot) throws Exception {
        java.nio.file.Path reportsDir = repoRoot.resolve("target/surefire-reports");
        java.nio.file.Files.createDirectories(reportsDir);
        java.nio.file.Files.copy(
                java.nio.file.Path.of("fixtures/surefire-xml/TEST-com.example.UserServiceTest.xml"),
                reportsDir.resolve("TEST-com.example.UserServiceTest.xml"));

        ParsedFailure result = FailureLogParser.parse(repoRoot, "this text does not matter and would fail regex parsing");

        assertEquals("java.lang.NullPointerException", result.exceptionType());
        assertEquals(List.of("UserServiceTest.testPremiumDiscount"), result.failingTests());
    }

    @Test
    void fallsBackToConsoleLogWhenNoXmlReportsPresent(@org.junit.jupiter.api.io.TempDir java.nio.file.Path repoRoot) {
        ParsedFailure result = FailureLogParser.parse(repoRoot, JAVA_LOG);

        assertEquals("java.lang.NullPointerException", result.exceptionType());
        assertEquals(4, result.frames().size());
    }
}
