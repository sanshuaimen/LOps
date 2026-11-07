package com.cifa.evolution;

/**
 * The two kinds of behavior asset a distilled experience can crystallize into
 * (the evolution plan's "memory → skill" upgrade, step 3):
 *
 * <ul>
 *   <li>{@link #ROUTING} — compiles into the deterministic investigation
 *       layer: an ordered tool preference for a symptom class. Never travels
 *       as free prompt text; the router renders it into the fixed routing
 *       block of the system prompt. Promoting a ROUTING skill changes
 *       behavior structurally, so it requires an explicit approver
 *       (EVOLUTION_APPROVAL) — the plan's high-risk-change human gate.</li>
 *   <li>{@link #PROMPT} — a short instruction fragment injected into the
 *       system prompt after a deterministic symptom match (the instruction
 *       layer of the three-layer disclosure: index stays in the router,
 *       instruction appears only on match, provenance/evidence stays
 *       retrievable on demand).</li>
 * </ul>
 */
public enum SkillKind {
    ROUTING,
    PROMPT
}
