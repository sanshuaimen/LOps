package com.cifa.parsing;

public record StackFrame(String className, String methodName, String fileName, int lineNumber) {
}
