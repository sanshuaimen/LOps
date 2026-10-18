package com.cifa.agent;

import com.cifa.llm.AgentDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConclusionPolicyTest {

    @Test
    void acceptsWellCitedSubmission() {
        ConclusionPolicy.Verdict verdict = ConclusionPolicy.evaluate(
                AgentDecision.submit("LOGIC_ERROR", "null check missing", List.of("LOG-001"), 80, "add guard"), 1);

        assertTrue(verdict.accepted());
        assertFalse(verdict.asUnknown());
    }

    @Test
    void rejectsSubmissionWithBlankRootCause() {
        ConclusionPolicy.Verdict verdict = ConclusionPolicy.evaluate(
                AgentDecision.submit("LOGIC_ERROR", "", List.of("LOG-001"), 80, ""), 1);

        assertFalse(verdict.accepted());
        assertTrue(verdict.reason().contains("empty rootCause"));
    }

    @Test
    void rejectsSubmissionWithoutEvidence() {
        ConclusionPolicy.Verdict verdict = ConclusionPolicy.evaluate(
                AgentDecision.submit("LOGIC_ERROR", "a guess", List.of(), 90, "fix something"), 0);

        assertFalse(verdict.accepted());
        assertTrue(verdict.reason().contains("no evidence cited"));
    }

    @Test
    void categoryUnknownBecomesUnknownOutcome() {
        ConclusionPolicy.Verdict verdict = ConclusionPolicy.evaluate(
                AgentDecision.submit("UNKNOWN", "cannot determine", List.of("LOG-001"), 40, "try restarting"),
                1);

        assertTrue(verdict.accepted());
        assertTrue(verdict.asUnknown());
    }

    @Test
    void unknownDecisionMustNotCarryASuggestedAction() {
        AgentDecision withAction = new AgentDecision(AgentDecision.Kind.UNKNOWN, "insufficient", "",
                java.util.Map.of(), "UNKNOWN", "insufficient", List.of(), 0, "redeploy and hope");

        ConclusionPolicy.Verdict verdict = ConclusionPolicy.evaluate(withAction, 0);

        assertFalse(verdict.accepted());
        assertTrue(verdict.reason().contains("must not carry a suggested action"));
    }

    @Test
    void plainUnknownDecisionIsAccepted() {
        ConclusionPolicy.Verdict verdict = ConclusionPolicy.evaluate(
                AgentDecision.unknown("log contains no usable failure"), 0);

        assertTrue(verdict.accepted());
        assertTrue(verdict.asUnknown());
    }
}
