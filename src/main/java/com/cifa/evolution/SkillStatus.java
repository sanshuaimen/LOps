package com.cifa.evolution;

/**
 * Lifecycle of a validated-experience skill (the evolution plan's five-state
 * machine, extended with the conflict quarantine). Transitions are legal ONLY
 * along the edges below; {@link SkillsStore} refuses anything else, and
 * ARCHIVED is a tombstone: the entry is kept forever, never physically
 * deleted, so "what was tried" stays auditable.
 *
 * <pre>
 * CANDIDATE ──promote──▶ ACTIVE ──decay──▶ STALE ──reactivate──▶ ACTIVE
 *    │  │                  │  │                │
 *    │  └─conflict─▶ CONFLICTED               └─archive──▶ ARCHIVED
 *    │                 │  │
 *    └─reject──▶ ARCHIVED │
 *                      │  └─arbitrate──▶ ACTIVE / ARCHIVED
 * ACTIVE ──supersede──▶ SUPERSEDED ──archive──▶ ARCHIVED
 * </pre>
 *
 * <p>Two distinct "no longer active" reasons are deliberately NOT merged:
 * STALE means "long inactive / low survival score" (the decay mechanism),
 * SUPERSEDED means "overturned by a newer validated rule" — a superseded
 * skill keeps its {@code supersededBy} link so the lineage stays readable.</p>
 */
public enum SkillStatus {
    CANDIDATE,
    ACTIVE,
    STALE,
    SUPERSEDED,
    CONFLICTED,
    ARCHIVED;

    /** Legal edges of the state machine; everything else is refused by the store. */
    public static boolean canTransition(SkillStatus from, SkillStatus to) {
        return switch (from) {
            case CANDIDATE -> to == SkillStatus.ACTIVE
                    || to == SkillStatus.CONFLICTED
                    || to == SkillStatus.ARCHIVED;
            case CONFLICTED -> to == SkillStatus.ACTIVE
                    || to == SkillStatus.ARCHIVED;
            case ACTIVE -> to == SkillStatus.STALE
                    || to == SkillStatus.SUPERSEDED
                    || to == SkillStatus.ARCHIVED;
            case STALE -> to == SkillStatus.ACTIVE
                    || to == SkillStatus.ARCHIVED;
            case SUPERSEDED -> to == SkillStatus.ARCHIVED;
            case ARCHIVED -> false; // tombstone: terminal by design
        };
    }
}
