package com.cifa.agent;

/**
 * Why an investigation stopped. Only COMPLETED and INSUFFICIENT_EVIDENCE are
 * model- or evidence-driven conclusions; everything else is a controlled
 * termination by deterministic code. JUDGE_UNSUPPORTED is the third
 * conclusion-shaped value: an honest "submitted, but the semantic review
 * rejected it" outcome — kept distinct from INSUFFICIENT_EVIDENCE so the
 * reasonable-unknown metric never conflates "agent found nothing" with
 * "review overturned a claim".
 */
public enum AgentStopReason {
    COMPLETED,
    INSUFFICIENT_EVIDENCE,
    JUDGE_UNSUPPORTED,
    MAX_ROUNDS,
    TOKEN_BUDGET_EXHAUSTED,
    TIME_BUDGET_EXHAUSTED,
    CONSECUTIVE_TOOL_FAILURES,
    NO_PROGRESS,
    MODEL_FAILURE,
    CANCELLED
}
