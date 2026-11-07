package com.cifa.evolution;

import com.cifa.memory.FailureMemoryEntry;
import com.cifa.memory.FailureMemoryRetrieval;
import com.cifa.memory.FailureMemoryStore;
import com.cifa.memory.MemoryDistiller;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The flywheel's reflow pipe (data-flywheel Tier 0 + the plan's Module A):
 * batch-harvests completed diagnosis traces, distills each into (a) semantic
 * memory via {@link MemoryDistiller} and (b) skill candidates via
 * {@link SkillProposer}, both through their own deterministic gates, with the
 * fixed holdout DOUBLE-BLIND enforced at the door — traces sourced from
 * holdout cases are refused before any distillation sees them.
 *
 * <p>Honest accounting throughout: every trace ends in exactly one of
 * {memory-added, memory-updated, skill-candidate, skill-conflicted, blocked,
 * skipped} and the {@link Report} names the counts — a harvest that mostly
 * skips is a fine outcome, never a silent one.</p>
 */
public final class HarvestPipeline {

    public record Report(int tracesRead, int holdoutBlocked, int memoryAdded, int memoryUpdated,
                         int memorySkipped, int skillsProposed, int skillsConflicted,
                         int skillsSkipped) {

        public String render() {
            return "harvest: traces=" + tracesRead + " holdout-blocked=" + holdoutBlocked
                    + " memory(+" + memoryAdded + "/~" + memoryUpdated + "/skip " + memorySkipped + ")"
                    + " skills(+" + skillsProposed + "/conflict " + skillsConflicted + "/skip " + skillsSkipped + ")";
        }
    }

    /** One parsed trace: what the distillers need from a JSONL file. */
    record ParsedTrace(String taskId, String caseId, String traceText, String verdictSummary,
                       String signatureHint, int tokensUsed) {
    }

    private final FailureMemoryStore memoryStore;   // nullable: memory path off
    private final SkillsStore skillsStore;          // nullable: skill path off
    private final RejectedPathStore rejectedPaths;  // nullable
    private final CorpusPartition partition;        // nullable: no holdout guard (live traces only)
    private final MemoryDistiller memoryDistiller;
    private final SkillProposer skillProposer;
    private final int memoryK;
    /** caseId → deterministic recall signature (the case log's first exception
     * line), wired by the CLI; null leaves signatures to the model (distill's
     * documented fallback). Eval traces are the precise path: their caseIds
     * resolve into the fixture corpus. */
    private final java.util.function.Function<String, String> signatureFor;

    public HarvestPipeline(FailureMemoryStore memoryStore, SkillsStore skillsStore,
                           RejectedPathStore rejectedPaths, CorpusPartition partition,
                           MemoryDistiller memoryDistiller, SkillProposer skillProposer, int memoryK,
                           java.util.function.Function<String, String> signatureFor) {
        this.memoryStore = memoryStore;
        this.skillsStore = skillsStore;
        this.rejectedPaths = rejectedPaths;
        this.partition = partition;
        this.memoryDistiller = memoryDistiller;
        this.skillProposer = skillProposer;
        this.memoryK = memoryK;
        this.signatureFor = signatureFor;
    }

    public Report run(List<Path> traceFiles) throws IOException, InterruptedException {
        int traces = 0;
        int blocked = 0;
        int memoryAdded = 0;
        int memoryUpdated = 0;
        int memorySkipped = 0;
        int skillsProposed = 0;
        int skillsConflicted = 0;
        int skillsSkipped = 0;

        for (Path traceFile : traceFiles) {
            ParsedTrace parsed = parse(traceFile);
            if (parsed == null) {
                continue;
            }
            traces++;

            // DOUBLE-BLIND, door one: holdout-sourced traces never reach a
            // distiller — refused and counted, never silently distilled
            if (parsed.caseId() != null && partition != null
                    && partition.isHoldout(parsed.caseId())) {
                blocked++;
                continue;
            }

            boolean touched = false;
            String signature = parsed.signatureHint();
            if (signature == null && parsed.caseId() != null && signatureFor != null) {
                signature = signatureFor.apply(parsed.caseId());
            }
            if (memoryStore != null) {
                MemoryDistiller.Distillation distillation = memoryDistiller.distill(
                        parsed.traceText(), parsed.caseId() != null ? parsed.caseId() : parsed.taskId(),
                        signature);
                if (distillation.candidate() == null) {
                    memorySkipped++;
                } else {
                    List<FailureMemoryEntry> similar = new FailureMemoryRetrieval(memoryStore.entries())
                            .retrieve(String.join(" ", distillation.candidate().retrievalTokens()),
                                    distillation.candidate().caseId(), memoryK, 0.0);
                    MemoryDistiller.Consolidation consolidation =
                            memoryDistiller.consolidate(distillation.candidate(), similar);
                    switch (consolidation.action()) {
                        case ADD -> {
                            memoryStore.append(consolidation.entry());
                            memoryAdded++;
                            touched = true;
                        }
                        case UPDATE -> {
                            memoryStore.update(consolidation.entry());
                            memoryUpdated++;
                            touched = true;
                        }
                        case NOOP -> memorySkipped++;
                    }
                }
            }

            if (skillsStore != null) {
                List<SkillRule> active = skillsStore.active();
                List<RejectedPathStore.RejectedPath> relevant = rejectedPaths == null
                        ? List.of()
                        : rejectedPaths.relevantFor(signature, SkillProposer.MAX_REJECTED_PATHS);
                SkillProposer.Proposal proposal = skillProposer.propose(
                        parsed.traceText(), parsed.verdictSummary(), active, relevant,
                        signature, traceFile.getFileName().toString());
                if (proposal.candidate() == null) {
                    skillsSkipped++;
                } else {
                    try {
                        SkillRule stored = skillsStore.append(proposal.candidate(), "harvest",
                                traceFile.getFileName().toString());
                        if (stored.status() == SkillStatus.CONFLICTED) {
                            skillsConflicted++;
                        } else {
                            skillsProposed++;
                        }
                        touched = true;
                    } catch (IllegalArgumentException e) {
                        // duplicate refusal: the graveyard already knows this advice —
                        // record the walk as negative knowledge, not silence
                        if (rejectedPaths != null && e.getMessage() != null
                                && e.getMessage().contains("duplicate")) {
                            rejectedPaths.append(new RejectedPathStore.RejectedPath(
                                    signature == null ? "" : signature,
                                    proposal.candidate().name(),
                                    "duplicate of an existing (possibly archived) skill: " + e.getMessage(),
                                    traceFile.getFileName().toString(), "", 1));
                        }
                        skillsSkipped++;
                    }
                }
            }
            if (!touched && memoryStore == null && skillsStore == null) {
                skillsSkipped++; // no destination configured: honest accounting, not a silent no-op
            }
        }
        return new Report(traces, blocked, memoryAdded, memoryUpdated, memorySkipped,
                skillsProposed, skillsConflicted, skillsSkipped);
    }

    /**
     * Parses one JSONL trace into the shape the distillers need. Eval runs name
     * their taskIds "eval-&lt;caseId&gt;" — that convention is what makes the
     * holdout guard enforceable; live task ids carry no caseId and pass freely.
     */
    static ParsedTrace parse(Path traceFile) throws IOException {
        if (!Files.exists(traceFile)) {
            return null;
        }
        String taskId = null;
        StringBuilder text = new StringBuilder();
        String category = "";
        String stopReason = "";
        int tokens = 0;
        for (String line : Files.readAllLines(traceFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JSONObject event;
            try {
                event = new JSONObject(line);
            } catch (Exception e) {
                continue; // torn tail: keep the parseable prefix
            }
            if (taskId == null) {
                taskId = event.optString("taskId", traceFile.getFileName().toString());
            }
            String eventType = event.optString("eventType", "");
            tokens += event.optInt("tokenCount", 0);
            String hypothesis = event.optString("hypothesis", "");
            switch (eventType) {
                case "tool_call" -> text.append("[round ").append(event.optInt("round"))
                        .append("] tool ").append(event.optString("toolName"))
                        .append(" → ").append(event.optString("status"))
                        .append(" | checking: ").append(truncate(hypothesis, 160)).append('\n');
                case "model_decision" -> { /* decision content arrives via tool/conclusion events */ }
                case "judgment" -> text.append("[judge] ").append(event.optString("status"))
                        .append(": ").append(truncate(event.optString("error"), 200)).append('\n');
                case "conclusion" -> {
                    stopReason = event.optString("stopReason", event.optString("status", ""));
                    category = categoryOf(hypothesis);
                    text.append("[conclusion] ").append(stopReason)
                            .append(" category=").append(category.isEmpty() ? "?" : category)
                            .append(" | ").append(truncate(hypothesis, 300)).append('\n');
                }
                case "stopped", "model_failure" -> text.append("[").append(eventType).append("] ")
                        .append(truncate(event.optString("error", event.optString("stopReason", "")), 200))
                        .append('\n');
                default -> { }
            }
        }
        if (taskId == null) {
            return null;
        }
        String caseId = taskId.startsWith("eval-") ? taskId.substring("eval-".length()) : null;
        String verdict = "stopReason=" + (stopReason.isBlank() ? "?" : stopReason)
                + " category=" + (category.isBlank() ? "?" : category)
                + " tokens=" + tokens;
        return new ParsedTrace(taskId, caseId, text.toString(), verdict, null, tokens);
    }

    /** The submitted category from a conclusion event's decision JSON, if present. */
    private static String categoryOf(String hypothesis) {
        try {
            JSONObject decision = new JSONObject(hypothesis);
            return decision.optString("category", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String value, int cap) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= cap ? flat : flat.substring(0, cap) + "...";
    }
}
