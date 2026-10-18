package com.cifa.agent;

import java.time.Duration;

/**
 * Hard limits for one investigation. Enforced by deterministic code — the
 * model cannot talk its way past them.
 */
public record InvestigationBudget(
        int maxRounds,
        int maxTokens,
        Duration maxWallClock,
        int maxConsecutiveToolFailures,
        int maxRepeatsWithoutProgress,
        int modelRetries
) {
    public InvestigationBudget {
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds must be positive");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (maxWallClock == null || maxWallClock.isNegative() || maxWallClock.isZero()) {
            throw new IllegalArgumentException("maxWallClock must be positive");
        }
        if (maxConsecutiveToolFailures < 1) {
            throw new IllegalArgumentException("maxConsecutiveToolFailures must be positive");
        }
        if (maxRepeatsWithoutProgress < 1) {
            throw new IllegalArgumentException("maxRepeatsWithoutProgress must be positive");
        }
        if (modelRetries < 0) {
            throw new IllegalArgumentException("modelRetries must be non-negative");
        }
    }

    public static InvestigationBudget defaults() {
        return new InvestigationBudget(8, 50_000, Duration.ofMinutes(5), 3, 2, 2);
    }
}
