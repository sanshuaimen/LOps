package com.cifa.parsing;

import java.util.List;

public record ParsedFailure(
        String exceptionType,
        String message,
        List<StackFrame> frames,
        List<String> failingTests,
        String logExcerpt
) {
}
