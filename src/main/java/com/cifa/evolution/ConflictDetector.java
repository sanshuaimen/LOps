package com.cifa.evolution;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic pre-store arbitration (the plan's conflict mechanism, minus
 * any automatic resolution): when a new candidate and an already-ACTIVE skill
 * match the same symptom class, the candidate either duplicates existing
 * advice or contradicts it. Contradiction NEVER overwrites the active rule —
 * the candidate is quarantined as CONFLICTED and waits for a human verdict
 * ({@code evolution arbitrate}); the active rule keeps governing until then.
 *
 * <p>There is intentionally no confidence score, no automatic winner: at
 * this store's scale a Bayesian win-rate would be statistics theater. The
 * honest deterministic split is NEW / DUPLICATE / CONFLICT.</p>
 */
public final class ConflictDetector {

    public enum Classification { NEW, DUPLICATE, CONFLICT }

    public record Verdict(Classification classification, SkillRule against, String reason) {
    }

    private ConflictDetector() {
    }

    /**
     * Classifies {@code candidate} against the store. Match = category overlap
     * (equal, or either side ANY) plus at least one shared symptom keyword.
     * Two passes with different scopes:
     *
     * <ul>
     *   <li><b>DUPLICATE</b> scans EVERY status including ARCHIVED tombstones —
     *       "this advice was already stored (and maybe already failed)" is
     *       exactly what the negative-knowledge ledger is for.</li>
     *   <li><b>CONFLICT</b> scans ACTIVE skills only — a contradiction matters
     *       when the other advice actually governs behavior; contradicting an
     *       archived corpse is a fresh proposal, not a conflict.</li>
     * </ul>
     *
     * No match anywhere → NEW.
     */
    public static Verdict classify(SkillRule candidate, List<SkillRule> allSkills) {
        for (SkillRule other : allSkills) {
            if (!categoryOverlap(candidate.category(), other.category())) {
                continue;
            }
            String shared = sharedKeyword(candidate.symptomKeywords(), other.symptomKeywords());
            if (shared == null) {
                continue;
            }
            if (adviceEquals(candidate, other)) {
                return new Verdict(Classification.DUPLICATE, other,
                        other.status() + " skill " + other.id() + " already teaches the same advice (shared symptom '"
                                + shared + "', category " + other.category() + ")");
            }
            if (other.isActive()) {
                return new Verdict(Classification.CONFLICT, other,
                        "contradicts active skill " + other.id() + " on the same symptom class (shared '"
                                + shared + "', category " + other.category() + "): candidate "
                                + describeAdvice(candidate) + " vs active " + describeAdvice(other)
                                + " — quarantined for human arbitration");
            }
        }
        return new Verdict(Classification.NEW, null, "no stored skill matches this symptom class");
    }

    static boolean categoryOverlap(String a, String b) {
        return a.equalsIgnoreCase(b)
                || a.equalsIgnoreCase(SkillRule.ANY_CATEGORY)
                || b.equalsIgnoreCase(SkillRule.ANY_CATEGORY);
    }

    static String sharedKeyword(List<String> a, List<String> b) {
        for (String left : a) {
            String leftLower = left.toLowerCase(Locale.ROOT);
            for (String right : b) {
                if (right.toLowerCase(Locale.ROOT).equals(leftLower)) {
                    return left;
                }
            }
        }
        return null;
    }

    /** Advice equality is exact and kind-aware — paraphrases are conflicts, not duplicates;
     * the distiller's job is de-case-ifying, so genuinely identical lessons converge
     * byte-identical through the content-addressed id. */
    static boolean adviceEquals(SkillRule a, SkillRule b) {
        if (a.kind() != b.kind()) {
            return false;
        }
        return switch (a.kind()) {
            case ROUTING -> a.routingTools().equals(b.routingTools());
            case PROMPT -> a.instruction().equalsIgnoreCase(b.instruction().strip());
        };
    }

    private static String describeAdvice(SkillRule skill) {
        return switch (skill.kind()) {
            case ROUTING -> "routes [" + String.join(" → ", skill.routingTools()) + "]";
            case PROMPT -> "instructs \"" + (skill.instruction().length() <= 80
                    ? skill.instruction() : skill.instruction().substring(0, 80) + "...") + "\"";
        };
    }
}
