package com.cifa.evolution;

import java.util.ArrayList;
import java.util.List;

/**
 * The decay/forgetting mechanism (evolution component 6): every ACTIVE skill
 * gets a survival score from its usage telemetry and gate-validated gain;
 * below-threshold skills go STALE — dormant (never injected by the router),
 * tombstone intact, revivable via {@code evolution reactivate}.
 *
 * <p>Score (documented, deterministic, injectable clock for tests):
 * {@code 2.0·liveHits + 0.5·evalHits + 2.0·(wins>0) + 1.0·(hit within half-life)}.
 * Live signal dominates: a skill exercised only by the exam is not the same
 * signal as one exercised by real investigations. Threshold default 1.0 —
 * i.e. one real live hit ever (plus nothing) survives; zero-use skills decay
 * at the end of their half-life window.</p>
 *
 * <p><b>Data-volume precondition</b> (the plan's hard gate for decay demos):
 * the engine refuses to run on a library under {@code minStore} ACTIVE skills —
 * decay on an empty library is governance theater, and refusing loudly is the
 * honest posture.</p>
 */
public final class DecayEngine {

    public static final double DEFAULT_THRESHOLD = 1.0;
    public static final long DEFAULT_HALF_LIFE_DAYS = 14;
    public static final int DEFAULT_MIN_STORE = 10;

    public record Decision(String skillId, double score, boolean markedStale, String reason) {
    }

    private DecayEngine() {
    }

    /** Deterministic survival score; {@code nowMillis} is injected so tests and
     * dry-runs are reproducible. */
    public static double survivalScore(SkillUsageStore.Usage usage, int validatedWins,
                                       long nowMillis, long halfLifeDays) {
        double score = 2.0 * usage.liveHits() + 0.5 * usage.evalHits();
        if (validatedWins > 0) {
            score += 2.0;
        }
        long halfLifeMillis = halfLifeDays * 24L * 60L * 60L * 1000L;
        if (usage.lastHitEpochMillis() > 0
                && nowMillis - usage.lastHitEpochMillis() <= halfLifeMillis) {
            score += 1.0;
        }
        return score;
    }

    public static List<Decision> run(SkillsStore store, SkillUsageStore usage, double threshold,
                                     long halfLifeDays, int minStore, long nowMillis,
                                     String actor, String basedOn) throws java.io.IOException {
        List<SkillRule> active = store.active();
        if (active.size() < minStore) {
            throw new IllegalStateException("decay refused: only " + active.size()
                    + " ACTIVE skills (min " + minStore + ") — grow the library first"
                    + " (harvest more traces); decay on an empty library is theater");
        }
        List<Decision> decisions = new ArrayList<>();
        for (SkillRule skill : active) {
            SkillUsageStore.Usage stats = usage.usage(skill.id());
            double score = survivalScore(stats, skill.wins(), nowMillis, halfLifeDays);
            if (score < threshold) {
                store.markStale(skill.id(), actor,
                        String.format(java.util.Locale.ROOT,
                                "survival %.2f below threshold %.2f (live=%d eval=%d wins=%d)",
                                score, threshold, stats.liveHits(), stats.evalHits(), skill.wins()),
                        basedOn);
                decisions.add(new Decision(skill.id(), score, true, "below threshold → dormant"));
            } else {
                decisions.add(new Decision(skill.id(), score, false, "survives"));
            }
        }
        return decisions;
    }
}
