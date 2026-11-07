package com.cifa.evolution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The deterministic half of the runtime injection (evolution plan component
 * 4): matches ACTIVE skills against a failure's category + signature in plain
 * code — the model never chooses which skill applies. Disclosure is
 * three-layer and progressive:
 *
 * <ol>
 *   <li><b>index layer</b> — lives HERE, in the router; it never enters the
 *       prompt (the model does not see the skill catalog at all).</li>
 *   <li><b>instruction layer</b> — only matched skills' fragments are rendered
 *       into the system prompt by the agent loop.</li>
 *   <li><b>evidence layer</b> — provenance stays in skills.json, retrievable
 *       on demand ({@code evolution show}); never injected.</li>
 * </ol>
 *
 * <p>Every match records a usage hit (the decay mechanism's input), tagged by
 * source so live-diagnosis signal stays separable from eval exercise.</p>
 */
public final class SkillsRouter {

    /** Rendered routing/instruction block: bounded so a large active set can
     * never blow the context budget — the router caps, deterministic. */
    static final int MAX_INJECTED_SKILLS = 4;
    static final int MAX_SIGNATURE_CHARS = 600;

    /** One matched skill ready for prompt injection. */
    public record Match(SkillRule skill) {
        public String render() {
            return switch (skill.kind()) {
                case ROUTING -> "- validated routing [" + skill.name() + "]: for symptoms like \""
                        + joinKeywords(skill.symptomKeywords()) + "\" prefer tools in this order: "
                        + String.join(" → ", skill.routingTools());
                case PROMPT -> "- validated practice [" + skill.name() + "]: " + skill.instruction();
            };
        }
    }

    private final List<SkillRule> activeSkills;
    private final SkillUsageStore usage;
    private final SkillUsageStore.Source usageSource;

    public SkillsRouter(List<SkillRule> activeSkills, SkillUsageStore usage, SkillUsageStore.Source source) {
        this.activeSkills = List.copyOf(activeSkills);
        this.usage = usage;
        this.usageSource = source;
    }

    /** Reads-only router: matches without recording usage (eval's default posture). */
    public static SkillsRouter observeOnly(List<SkillRule> activeSkills) {
        return new SkillsRouter(activeSkills, null, SkillUsageStore.Source.EVAL);
    }

    /**
     * Deterministic match: ACTIVE skills whose category overlaps the failure's
     * category and whose keywords hit the signature text. Newest matches cap at
     * {@value #MAX_INJECTED_SKILLS}; records a usage hit per matched skill.
     */
    public List<Match> route(String failureCategory, String signatureText) {
        String signature = signatureText == null ? "" : signatureText.toLowerCase(Locale.ROOT);
        if (signature.length() > MAX_SIGNATURE_CHARS) {
            signature = signature.substring(0, MAX_SIGNATURE_CHARS);
        }
        List<Match> matches = new ArrayList<>();
        for (SkillRule skill : activeSkills) {
            if (matches.size() >= MAX_INJECTED_SKILLS) {
                break;
            }
            if (skill.matchesCategory(failureCategory) && skill.matchesSymptoms(signature)) {
                matches.add(new Match(skill));
                if (usage != null) {
                    usage.recordHit(skill.id(), usageSource);
                }
            }
        }
        return matches;
    }

    /** Convenience: does anything match? Lets callers skip prompt surgery when empty. */
    public boolean isEmpty(String failureCategory, String signatureText) {
        return route(failureCategory, signatureText).isEmpty();
    }

    private static String joinKeywords(List<String> keywords) {
        return String.join(", ", keywords);
    }
}
