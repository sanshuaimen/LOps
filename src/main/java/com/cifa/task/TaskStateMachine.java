package com.cifa.task;

import com.cifa.agent.AgentStopReason;

/**
 * Authoritative transition matrix and terminal-state mappings (P1 task 2).
 * The Flyway CHECK constraint mirrors this class, never the other way round.
 *
 * <p>Two terminal mappings exist on purpose and mean different things:
 * an agent investigation that ends with {@code AgentStopReason.INSUFFICIENT_EVIDENCE}
 * lands in {@link TaskStatus#UNKNOWN} (a normal diagnostic outcome), while the
 * baseline analyzer has no stop reason — success is always SUCCEEDED even when
 * the diagnosis text says UNKNOWN. "Task SUCCEEDED with unknown content"
 * (baseline) and "task terminal state UNKNOWN" (agent) are both legal and not
 * interchangeable.
 */
public final class TaskStateMachine {

    private TaskStateMachine() {
    }

    public static void requireTransition(TaskStatus from, TaskStatus to) {
        if (!isLegal(from, to)) {
            throw new IllegalStateException("illegal task transition " + from + " -> " + to);
        }
    }

    public static boolean isLegal(TaskStatus from, TaskStatus to) {
        return switch (from) {
            case PENDING -> to == TaskStatus.RUNNING || to == TaskStatus.CANCELLED
                    // restart recovery: the worker died between insert and enqueue
                    || to == TaskStatus.FAILED;
            case RUNNING -> to.isTerminal();
            case SUCCEEDED, UNKNOWN, FAILED, CANCELLED -> false;
        };
    }

    /** agent path: every AgentStopReason must map somewhere — exhaustive on purpose. */
    public static TaskStatus terminalForAgent(AgentStopReason stopReason) {
        return switch (stopReason) {
            case COMPLETED -> TaskStatus.SUCCEEDED;
            case INSUFFICIENT_EVIDENCE, MAX_ROUNDS, TOKEN_BUDGET_EXHAUSTED, TIME_BUDGET_EXHAUSTED,
                    NO_PROGRESS, CONSECUTIVE_TOOL_FAILURES -> TaskStatus.UNKNOWN;
            case MODEL_FAILURE -> TaskStatus.FAILED;
            case CANCELLED -> TaskStatus.CANCELLED;
        };
    }

    /** baseline path: execution success/failure only; diagnosis content is not consulted. */
    public static TaskStatus terminalForBaseline(boolean executionSucceeded) {
        return executionSucceeded ? TaskStatus.SUCCEEDED : TaskStatus.FAILED;
    }
}
