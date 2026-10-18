package com.cifa.agent;

import com.cifa.llm.AgentDecision;

/**
 * Layer 2 of conclusion control: deterministic floor rules on semantic
 * sufficiency. P0 implements exactly three floors, deliberately narrow:
 *   1. a submission with zero validated evidence is rejected (cite or declare unknown);
 *   2. a blank rootCause is rejected;
 *   3. an unknown outcome must not carry a suggested action.
 * Grading evidence as "weak/strong" and clamping confidence accordingly is a
 * semantic judgment a Java rule would fake — that stays with eval, human
 * review, and the P2 verifier-agent experiment.
 */
public final class ConclusionPolicy {

    public record Verdict(boolean accepted, boolean asUnknown, String reason) {
        public static Verdict accept() {
            return new Verdict(true, false, "");
        }

        public static Verdict acceptUnknown() {
            return new Verdict(true, true, "");
        }

        public static Verdict reject(String reason) {
            return new Verdict(false, false, reason);
        }
    }

    private ConclusionPolicy() {
    }

    public static Verdict evaluate(AgentDecision decision, int validatedEvidenceCount) {
        if (decision.kind() == AgentDecision.Kind.UNKNOWN) {
            if (!decision.suggestedAction().isBlank()) {
                return Verdict.reject("an unknown outcome must not carry a suggested action");
            }
            return Verdict.acceptUnknown();
        }
        if (decision.kind() != AgentDecision.Kind.SUBMIT) {
            return Verdict.reject("decision is not a conclusion");
        }
        if (decision.rootCause().isBlank()) {
            return Verdict.reject("submission has an empty rootCause");
        }
        if (decision.category().equalsIgnoreCase("UNKNOWN")) {
            return Verdict.acceptUnknown();
        }
        if (validatedEvidenceCount == 0) {
            return Verdict.reject("no evidence cited: cite evidence ids from this task, or declare unknown");
        }
        return Verdict.accept();
    }
}
