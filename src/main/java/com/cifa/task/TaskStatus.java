package com.cifa.task;

/**
 * Lifecycle of an async diagnosis task. Six states, no more — in particular
 * there is deliberately no REJECTED state: a rejected submission never becomes
 * a row (see the P1 plan, Option D), so FAILED always means "the task ran and
 * the system failed", never "it was never executed".
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    UNKNOWN,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == UNKNOWN || this == FAILED || this == CANCELLED;
    }
}
