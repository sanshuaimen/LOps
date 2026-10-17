package com.cifa.classification;

import com.cifa.parsing.ParsedFailure;

public record ClassifiedFailure(ParsedFailure failure, FailureCategory category) {
}
