package com.cifa.task;

import com.cifa.agent.AgentStopReason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskStateMachineTest {

    @Test
    void legalTransitionsAreAccepted() {
        List<Transition> legal = List.of(
                new Transition(TaskStatus.PENDING, TaskStatus.RUNNING),
                new Transition(TaskStatus.PENDING, TaskStatus.CANCELLED),
                new Transition(TaskStatus.PENDING, TaskStatus.FAILED),
                new Transition(TaskStatus.RUNNING, TaskStatus.SUCCEEDED),
                new Transition(TaskStatus.RUNNING, TaskStatus.UNKNOWN),
                new Transition(TaskStatus.RUNNING, TaskStatus.FAILED),
                new Transition(TaskStatus.RUNNING, TaskStatus.CANCELLED));

        for (Transition transition : legal) {
            assertDoesNotThrow(() -> TaskStateMachine.requireTransition(transition.from(), transition.to()),
                    () -> transition + " must be legal");
        }
    }

    @Test
    void everyOtherTransitionIsRejected() {
        for (TaskStatus from : TaskStatus.values()) {
            for (TaskStatus to : TaskStatus.values()) {
                if (TaskStateMachine.isLegal(from, to)) {
                    continue;
                }
                IllegalStateException e = assertThrows(IllegalStateException.class,
                        () -> TaskStateMachine.requireTransition(from, to),
                        () -> from + " -> " + to + " must be illegal");
                assertTrue(e.getMessage().contains("illegal"));
            }
        }
    }

    @Test
    void terminalStatesHaveNoExits() {
        for (TaskStatus terminal : List.of(TaskStatus.SUCCEEDED, TaskStatus.UNKNOWN,
                TaskStatus.FAILED, TaskStatus.CANCELLED)) {
            assertTrue(terminal.isTerminal());
            for (TaskStatus any : TaskStatus.values()) {
                assertFalse(TaskStateMachine.isLegal(terminal, any),
                        () -> terminal + " must have no exit, saw " + any);
            }
        }
        assertFalse(TaskStatus.PENDING.isTerminal());
        assertFalse(TaskStatus.RUNNING.isTerminal());
    }

    @Test
    void agentStopReasonsMapExhaustivelyAndExclusively() {
        assertEquals(TaskStatus.SUCCEEDED, TaskStateMachine.terminalForAgent(AgentStopReason.COMPLETED));
        assertEquals(TaskStatus.CANCELLED, TaskStateMachine.terminalForAgent(AgentStopReason.CANCELLED));
        assertEquals(TaskStatus.FAILED, TaskStateMachine.terminalForAgent(AgentStopReason.MODEL_FAILURE));

        for (AgentStopReason budgetStop : List.of(
                AgentStopReason.INSUFFICIENT_EVIDENCE, AgentStopReason.MAX_ROUNDS,
                AgentStopReason.TOKEN_BUDGET_EXHAUSTED, AgentStopReason.TIME_BUDGET_EXHAUSTED,
                AgentStopReason.NO_PROGRESS, AgentStopReason.CONSECUTIVE_TOOL_FAILURES)) {
            assertEquals(TaskStatus.UNKNOWN, TaskStateMachine.terminalForAgent(budgetStop),
                    () -> budgetStop + " is an honest-unknown stop, not a failure");
        }
        // all 9 reasons covered
        assertEquals(9, AgentStopReason.values().length);
    }

    @Test
    void modelFailureNeverLandsInUnknown() {
        // plan red line: 模型持续异常属于 FAILED，不能伪装成 UNKNOWN
        assertNotEquals(TaskStatus.UNKNOWN, TaskStateMachine.terminalForAgent(AgentStopReason.MODEL_FAILURE));
        assertEquals(TaskStatus.FAILED, TaskStateMachine.terminalForAgent(AgentStopReason.MODEL_FAILURE));
    }

    @Test
    void baselineSuccessIsSucceededEvenWhenDiagnosisContentIsUnknown() {
        // "task SUCCEEDED + unknown content" (baseline) is a different, legal semantics
        // from the agent's terminal UNKNOWN.
        assertEquals(TaskStatus.SUCCEEDED, TaskStateMachine.terminalForBaseline(true));
        assertEquals(TaskStatus.FAILED, TaskStateMachine.terminalForBaseline(false));
    }

    private record Transition(TaskStatus from, TaskStatus to) {
    }
}
