package com.cifa.correlation;

public record CorrelatedCommit(String hash, String author, String date, String diffHunk) {
}
