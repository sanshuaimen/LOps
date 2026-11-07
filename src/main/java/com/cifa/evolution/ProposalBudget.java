package com.cifa.evolution;

/**
 * Token budget for a proposal batch (the plan's meta-governance resource cap:
 * distillation and verification themselves must be bounded). The harvest
 * pipeline owns one budget per batch; every proposer call charges its actual
 * token usage, and an exhausted budget degrades to an honest SKIP — never a
 * partial write.
 */
public final class ProposalBudget {

    private final int maxTotalTokens;
    private int spentTokens;

    public ProposalBudget(int maxTotalTokens) {
        if (maxTotalTokens < 1) {
            throw new IllegalArgumentException("maxTotalTokens must be >= 1");
        }
        this.maxTotalTokens = maxTotalTokens;
    }

    /** Default batch budget: bounded, but roomy enough for a dozen proposals. */
    public static ProposalBudget defaults() {
        return new ProposalBudget(50_000);
    }

    /** Returns false when the budget is exhausted — the caller skips, honestly. */
    public boolean tryCharge(int tokens) {
        if (spentTokens + tokens > maxTotalTokens) {
            return false;
        }
        spentTokens += tokens;
        return true;
    }

    public boolean exhausted() {
        return spentTokens >= maxTotalTokens;
    }

    public int spentTokens() {
        return spentTokens;
    }

    public int maxTotalTokens() {
        return maxTotalTokens;
    }
}
