package com.cifa.parsing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FailureLogParser {

    private static final Pattern EXCEPTION_LINE =
            Pattern.compile("^([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*(?:Exception|Error)):?\\s*(.*)$");
    private static final Pattern STACK_FRAME_LINE =
            Pattern.compile("^\\s*at ([\\w$./]+)\\(([^:)]+)(?::(\\d+))?\\)\\s*$");
    private static final Pattern TEST_CLASS_LINE =
            Pattern.compile("<<< (?:FAILURE|ERROR)! - in ([\\w.]+)");
    private static final Pattern TEST_METHOD_LINE =
            Pattern.compile("^\\[ERROR]\\s+(\\S+)\\s+Time elapsed.*<<<");

    private static final Pattern PYTHON_TRACEBACK_START = Pattern.compile("^Traceback \\(most recent call last\\):$");
    private static final Pattern PYTHON_FRAME_LINE =
            Pattern.compile("^\\s*File \"([^\"]+)\", line (\\d+), in (\\S+)$");
    private static final Pattern PYTHON_EXCEPTION_LINE = Pattern.compile("^([\\w.]+): (.*)$");

    private static final Pattern NODE_EXCEPTION_LINE = Pattern.compile("^(\\w*Error): (.*)$");
    private static final Pattern NODE_FRAME_LINE =
            Pattern.compile("^\\s*at (\\S+) \\(([^:]+):(\\d+):(\\d+)\\)$");

    private FailureLogParser() {
    }

    public static ParsedFailure parse(String normalizedLog) {
        ParsedFailure javaResult = parseJava(normalizedLog);
        if (javaResult != null) {
            return javaResult;
        }
        ParsedFailure pythonResult = parsePython(normalizedLog);
        if (pythonResult != null) {
            return pythonResult;
        }
        ParsedFailure nodeResult = parseNode(normalizedLog);
        if (nodeResult != null) {
            return nodeResult;
        }
        return parseGenericFallback(normalizedLog);
    }

    public static ParsedFailure parse(java.nio.file.Path repoRoot, String normalizedLog) {
        ParsedFailure xmlResult = SurefireReportParser.parse(repoRoot);
        if (xmlResult != null) {
            return xmlResult;
        }
        return parse(normalizedLog);
    }

    private static ParsedFailure parseJava(String log) {
        String[] lines = log.split("\n", -1);

        String exceptionType = null;
        String message = null;
        List<StackFrame> frames = new ArrayList<>();
        List<String> excerptLines = new ArrayList<>();
        boolean inTraceBlock = false;

        String currentTestClass = null;
        List<String> failingTests = new ArrayList<>();

        for (String line : lines) {
            Matcher classMatch = TEST_CLASS_LINE.matcher(line);
            if (classMatch.find()) {
                String fqcn = classMatch.group(1);
                currentTestClass = fqcn.substring(fqcn.lastIndexOf('.') + 1);
                continue;
            }
            Matcher methodMatch = TEST_METHOD_LINE.matcher(line);
            if (methodMatch.find() && currentTestClass != null) {
                failingTests.add(currentTestClass + "." + methodMatch.group(1));
                continue;
            }

            if (exceptionType == null) {
                Matcher excMatch = EXCEPTION_LINE.matcher(line);
                if (excMatch.matches()) {
                    exceptionType = excMatch.group(1);
                    message = excMatch.group(2);
                    inTraceBlock = true;
                    excerptLines.add(line);
                    continue;
                }
            }

            if (inTraceBlock) {
                Matcher frameMatch = STACK_FRAME_LINE.matcher(line);
                if (frameMatch.matches()) {
                    String fqcnMethod = frameMatch.group(1);
                    int lastDot = fqcnMethod.lastIndexOf('.');
                    String className = fqcnMethod.substring(0, lastDot);
                    String methodName = fqcnMethod.substring(lastDot + 1);
                    String fileName = frameMatch.group(2);
                    int lineNumber = frameMatch.group(3) == null ? 0 : Integer.parseInt(frameMatch.group(3));
                    frames.add(new StackFrame(className, methodName, fileName, lineNumber));
                    excerptLines.add(line);
                    continue;
                }
                inTraceBlock = false;
            }
        }

        if (exceptionType == null || frames.isEmpty()) {
            return null;
        }
        return new ParsedFailure(exceptionType, message, frames, failingTests, String.join("\n", excerptLines));
    }

    private static ParsedFailure parsePython(String log) {
        String[] lines = log.split("\n");
        boolean sawTracebackStart = false;
        List<StackFrame> frames = new ArrayList<>();
        String exceptionType = null;
        String message = null;

        for (String line : lines) {
            if (PYTHON_TRACEBACK_START.matcher(line.trim()).matches()) {
                sawTracebackStart = true;
                continue;
            }
            if (!sawTracebackStart) {
                continue;
            }
            Matcher frameMatch = PYTHON_FRAME_LINE.matcher(line);
            if (frameMatch.matches()) {
                String file = frameMatch.group(1);
                String shortFile = file.substring(file.lastIndexOf('/') + 1);
                int lineNumber = Integer.parseInt(frameMatch.group(2));
                String funcName = frameMatch.group(3);
                frames.add(new StackFrame(file, funcName, shortFile, lineNumber));
                continue;
            }
            Matcher excMatch = PYTHON_EXCEPTION_LINE.matcher(line.trim());
            if (excMatch.matches() && !line.startsWith(" ")) {
                exceptionType = excMatch.group(1);
                message = excMatch.group(2);
            }
        }

        if (!sawTracebackStart || exceptionType == null) {
            return null;
        }
        return new ParsedFailure(exceptionType, message, frames, List.of(), log.trim());
    }

    private static ParsedFailure parseNode(String log) {
        String[] lines = log.split("\n");
        String exceptionType = null;
        String message = null;
        List<StackFrame> frames = new ArrayList<>();

        for (String line : lines) {
            if (exceptionType == null) {
                Matcher excMatch = NODE_EXCEPTION_LINE.matcher(line.trim());
                if (excMatch.matches()) {
                    exceptionType = excMatch.group(1);
                    message = excMatch.group(2);
                    continue;
                }
            }
            Matcher frameMatch = NODE_FRAME_LINE.matcher(line);
            if (frameMatch.matches()) {
                String funcName = frameMatch.group(1);
                String file = frameMatch.group(2);
                int lineNumber = Integer.parseInt(frameMatch.group(3));
                frames.add(new StackFrame(file, funcName, file, lineNumber));
            }
        }

        if (exceptionType == null || frames.isEmpty()) {
            return null;
        }
        return new ParsedFailure(exceptionType, message, frames, List.of(), log.trim());
    }

    private static ParsedFailure parseGenericFallback(String log) {
        for (String line : log.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.contains("FAILED") || trimmed.startsWith("[ERROR]") || trimmed.startsWith("ERROR:")) {
                return new ParsedFailure("UNKNOWN", trimmed, List.of(), List.of(), trimmed);
            }
        }
        return new ParsedFailure("UNKNOWN", "No recognizable failure pattern found in log.", List.of(), List.of(), "");
    }
}
