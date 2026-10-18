package com.cifa.agent;

/**
 * Why an investigation stopped. Only COMPLETED and INSUFFICIENT_EVIDENCE are
 * model- or evidence-driven conclusions; everything else is a controlled
 * termination by deterministic code.
 */
public enum AgentStopReason {
    COMPLETED,
    INSUFFICIENT_EVIDENCE,
    MAX_ROUNDS,
    TOKEN_BUDGET_EXHAUSTED,
    TIME_BUDGET_EXHAUSTED,
    CONSECUTIVE_TOOL_FAILURES,
    NO_PROGRESS,
    MODEL_FAILURE,
    CANCELLED
}
