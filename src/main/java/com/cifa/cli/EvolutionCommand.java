package com.cifa.cli;

import com.cifa.evolution.CorpusPartition;
import com.cifa.evolution.DecayEngine;
import com.cifa.evolution.EvolutionSnapshots;
import com.cifa.evolution.HarvestPipeline;
import com.cifa.evolution.PairedGatekeeper;
import com.cifa.evolution.ConflictDetector;
import com.cifa.evolution.ProposalBudget;
import com.cifa.evolution.RejectedPathStore;
import com.cifa.evolution.SkillAuditLog;
import com.cifa.evolution.SkillKind;
import com.cifa.evolution.SkillProposer;
import com.cifa.evolution.SkillRule;
import com.cifa.evolution.SkillStatus;
import com.cifa.evolution.SkillUsageStore;
import com.cifa.evolution.SkillsStore;
import com.cifa.eval.EvalReport;
import com.cifa.eval.EvalRunner;
import com.cifa.eval.GoldenCase;
import com.cifa.memory.FailureMemoryStore;
import com.cifa.memory.MemoryDistiller;
import com.cifa.service.AgentDiagnosisService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The evolution/flywheel control surface. Every mutation of the behavior
 * asset (skills.json) runs through here, which is what makes the governance
 * story real: partition (double-blind holdout), harvest (trace → memory +
 * skill candidates), stage (build the candidate side of an A/B), gate (paired
 * win/loss admission), arbitrate (human conflict verdict), decay (survival
 * score), snapshot/rollback (behavior version control), list/show (read),
 * inject (the Tier-1 corpus expansion harness).
 *
 * <p>All of it is offline; the 62→N golden corpus is the only judge. Real-model
 * runs are local manual commands with environment credentials, exactly like
 * eval — CI stays all-fake.</p>
 */
public final class EvolutionCommand {

    private static final String USAGE = """
            evolution — self-evolution & data-flywheel control surface

            Commands:
              partition  [--fixtures fixtures/eval] [--fraction 0.30] [--seed cifa-holdout-v1]
                         [--out partition.json]
                         Writes the deterministic stratified holdout/proposal split.
              harvest    --traces <glob,...> [--partition partition.json] [--memory failure-memory.json]
                         [--skills skills.json] [--rejected rejected-paths.json] [--fixtures fixtures/eval]
                         [--provider fake|ollama|anthropic|deepseek] [--model m] [--fake-script f]
                         [--budget 50000] [--k 3]
                         Reflows diagnosis traces into memory + skill candidates.
                         Holdout-sourced traces are refused (double-blind, door one).
              stage      --skills skills.json (--id <skillId> | --all-candidates) --out staged.json
                         Builds the candidate side: active set + the candidate(s) ACTIVE.
              gate       --baseline report.json --candidate report.json --partition partition.json
                         --skills skills.json --staged staged.json [--rejected rejected-paths.json]
                         [--min-wins 1] [--fixtures fixtures/eval]
                         Paired win/loss admission on the PROPOSAL set only; holdout ids
                         in either report are a hard refusal (double-blind, door two).
                         PROMOTE needs EVOLUTION_APPROVAL for ROUTING-kind skills.
                         REJECT tombstones the staged candidates and writes the
                         negative-knowledge ledger.
              arbitrate  --skills skills.json --id <skillId> --decision promote-new|archive-new
                         [--actor cifa] [--reason "..."]
                         The human verdict on a CONFLICTED candidate.
              decay      --skills skills.json --usage skill-usage.json [--threshold 1.0]
                         [--half-life-days 14] [--min-store 10] [--now-millis 0]
                         Survival-scored dormancy; refuses a library below --min-store.
              snapshot   --skills skills.json --eval-report <path> [--dataset-version v]
                         [--note "..."] [--actor cifa] [--dir target/evolution]
              rollback   --seq N --skills skills.json --dir target/evolution
                         Restores the snapshot's exact bytes (byte-identical contract).
              list       --skills skills.json [--status ACTIVE|CANDIDATE|...]
              show       --skills skills.json --id <skillId>
              inject     --repo <dir> --fault <dependency-version|remove-symbol|broken-import|flaky-test>
                         --value <v> [--out <dir>] [--dry-run]
                         Tier-1 corpus harness: inject a known fault into a real repo copy,
                         (optionally) build it for a REAL log, emit a golden-case skeleton
                         with the fault type as the free gold label.

            Defaults: skills.json / failure-memory.json / rejected-paths.json /
            partition.json / skill-usage.json in the working directory.
            """;

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, System.getenv()));
    }

    public static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err,
                          Map<String, String> env) {
        try {
            return execute(args, out, err, env);
        } catch (Usage e) {
            err.println("evolution: " + e.getMessage() + (e.usage ? "\n\n" + USAGE : ""));
            return 2;
        } catch (MemoryCommand.Usage e) {
            // GoldenCaseInjector raises the shared usage channel
            err.println("evolution: " + e.getMessage());
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("evolution interrupted");
            return 130;
        } catch (Exception e) {
            err.println("evolution failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return 1;
        }
    }

    private static final class Usage extends RuntimeException {
        private final boolean usage;

        Usage(String message, boolean usage) {
            super(message);
            this.usage = usage;
        }
    }

    /** Flags that are switches, not key-values — the parser must not eat a
     * following argument for them. */
    private static final java.util.Set<String> BOOLEAN_FLAGS =
            java.util.Set.of("--dry-run", "--all-candidates", "--skip-baseline");

    private static int execute(String[] args, java.io.PrintStream out, java.io.PrintStream err,
                               Map<String, String> env) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            out.print(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        String command = args[0];
        Map<String, String> flags = new java.util.LinkedHashMap<>();
        List<String> positionals = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                if (BOOLEAN_FLAGS.contains(arg)) {
                    flags.put(arg, "true");
                } else {
                    flags.put(arg, value(args, ++i, arg));
                }
            } else {
                positionals.add(arg);
            }
        }

        switch (command) {
            case "partition" -> { return partition(flags, out); }
            case "harvest" -> { return harvest(flags, out, err, env); }
            case "stage" -> { return stage(flags, out); }
            case "gate" -> { return gate(flags, out, err, env); }
            case "arbitrate" -> { return arbitrate(flags, out); }
            case "decay" -> { return decay(flags, out); }
            case "snapshot" -> { return snapshot(flags, out); }
            case "rollback" -> { return rollback(flags, out); }
            case "list" -> { return list(flags, out); }
            case "show" -> { return show(flags, out); }
            case "inject" -> { return inject(flags, out, err); }
            default -> throw new Usage("unknown command '" + command + "'", true);
        }
    }

    // ---- partition ---------------------------------------------------------

    private static int partition(Map<String, String> flags, java.io.PrintStream out) throws IOException {
        Path fixtures = Path.of(flags.getOrDefault("--fixtures", "fixtures/eval"));
        double fraction = Double.parseDouble(flags.getOrDefault("--fraction", "0.30"));
        String seed = flags.getOrDefault("--seed", CorpusPartition.DEFAULT_SEED);
        Path out_ = Path.of(flags.getOrDefault("--out", "partition.json"));

        List<GoldenCase> cases = GoldenCase.loadDirectory(fixtures);
        if (cases.isEmpty()) {
            throw new Usage("no golden cases under " + fixtures, false);
        }
        CorpusPartition partitioned = CorpusPartition.build(cases, fraction, seed);
        partitioned.save(out_);
        out.println("partition: " + partitioned.describe() + " → " + out_.toAbsolutePath());
        out.println("  holdout (double-blind: never in Proposer input, never in gate decisions):");
        partitioned.holdoutIds().stream().sorted().forEach(id -> out.println("    " + id));
        return 0;
    }

    // ---- harvest -----------------------------------------------------------

    private static int harvest(Map<String, String> flags, java.io.PrintStream out,
                               java.io.PrintStream err, Map<String, String> env) throws Exception {
        List<Path> traces = traceFiles(required(flags, "--traces"));
        if (traces.isEmpty()) {
            throw new Usage("--traces matched no files", false);
        }
        Path skillsFile = flags.containsKey("--skills") ? Path.of(flags.get("--skills")) : null;
        Path memoryFile = flags.containsKey("--memory") ? Path.of(flags.get("--memory")) : null;
        Path rejectedFile = flags.containsKey("--rejected") ? Path.of(flags.get("--rejected")) : null;
        CorpusPartition partitioned = flags.containsKey("--partition")
                ? CorpusPartition.load(Path.of(flags.get("--partition"))) : null;
        int budget = Integer.parseInt(flags.getOrDefault("--budget", "50000"));
        int k = Integer.parseInt(flags.getOrDefault("--k", "3"));

        if (skillsFile == null && memoryFile == null) {
            throw new Usage("nothing to harvest into: pass --skills and/or --memory", true);
        }

        SkillsStore skillsStore = skillsFile == null ? null : new SkillsStore(skillsFile,
                new SkillAuditLog(skillsFile.resolveSibling("skill-audit.jsonl")));
        FailureMemoryStore memoryStore = memoryFile == null ? null : new FailureMemoryStore(memoryFile);
        RejectedPathStore rejected = rejectedFile == null ? null : new RejectedPathStore(rejectedFile);

        LlmProviders.Selection selection = LlmProviders.create(
                flags.getOrDefault("--provider", "ollama"), flags.get("--model"), flags.get("--fake-script"));
        ProposalBudget proposalBudget = new ProposalBudget(budget);
        Set<String> knownTools = new AgentDiagnosisService().toolNames();
        SkillProposer proposer = new SkillProposer(selection.provider(), selection.model(),
                knownTools, proposalBudget);
        MemoryDistiller distiller = new MemoryDistiller(selection.provider(), selection.model());

        Path fixtures = Path.of(flags.getOrDefault("--fixtures", "fixtures/eval"));
        HarvestPipeline pipeline = new HarvestPipeline(memoryStore, skillsStore, rejected,
                partitioned, distiller, proposer, k, caseId -> signatureForCase(fixtures, caseId));

        HarvestPipeline.Report report = pipeline.run(traces);
        out.println(report.render());
        out.println("proposal budget: " + proposalBudget.spentTokens() + "/" + proposalBudget.maxTotalTokens());
        return 0;
    }

    private static List<Path> traceFiles(String globs) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String glob : globs.split(",")) {
            String trimmed = glob.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.contains("*")) {
                Path dir = Path.of(".").toAbsolutePath().normalize();
                try (Stream<Path> walk = Files.walk(dir, 1)) {
                    String pattern = trimmed.replace("*", "");
                    walk.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().contains(pattern))
                            .forEach(files::add);
                }
            } else {
                files.add(Path.of(trimmed));
            }
        }
        return files.stream().distinct().sorted().toList();
    }

    /** Eval traces carry "eval-<caseId>" task ids; the case's fixture log gives
     * the deterministic recall signature (first exception line). */
    private static String signatureForCase(Path fixtures, String caseId) {
        try {
            List<GoldenCase> matches = GoldenCase.loadDirectory(fixtures).stream()
                    .filter(c -> c.id().equals(caseId))
                    .toList();
            if (matches.size() != 1) {
                return null;
            }
            return MemoryCommand.firstExceptionLine(matches.get(0).logPath());
        } catch (IOException e) {
            return null;
        }
    }

    // ---- stage ---------------------------------------------------------------

    private static int stage(Map<String, String> flags, java.io.PrintStream out) throws IOException {
        Path skillsFile = Path.of(required(flags, "--skills"));
        Path out_ = Path.of(required(flags, "--out"));
        boolean all = flags.containsKey("--all-candidates");
        String id = flags.get("--id");
        if (!all == (id == null)) {
            throw new Usage("stage needs exactly one of --id <skillId> or --all-candidates", true);
        }
        SkillsStore store = new SkillsStore(skillsFile,
                new SkillAuditLog(skillsFile.resolveSibling("skill-audit.jsonl")));

        List<SkillRule> stagedSkills = new ArrayList<>();
        if (all) {
            for (SkillRule skill : store.all()) {
                stagedSkills.add(stagedVersionOf(store, skill.id()));
            }
        } else {
            stagedSkills.add(stagedVersionOf(store, id));
        }
        if (stagedSkills.isEmpty()) {
            throw new Usage("nothing to stage: no CANDIDATE skills found", false);
        }

        StringBuilder file = new StringBuilder();
        file.append("{\n  \"version\": 1,\n  \"skills\": [\n");
        for (int i = 0; i < stagedSkills.size(); i++) {
            file.append(stagedSkills.get(i).toJson().toString(2).replace("\n", "\n  "));
            if (i < stagedSkills.size() - 1) {
                file.append(',');
            }
            file.append('\n');
        }
        file.append("  ]\n}\n");
        Files.writeString(out_, file.toString());
        out.println("staged " + stagedSkills.size() + " skill(s) → " + out_.toAbsolutePath());
        stagedSkills.forEach(skill -> out.println("  " + skill.id() + " [" + skill.kind() + "] "
                + skill.name() + " (from " + skill.status() + " → staged ACTIVE)"));
        return 0;
    }

    /** The staged side of the A/B: the candidate ACTIVE, everything else as-is
     * (active skills stay active so the gate measures the candidate's DELTA). */
    private static SkillRule stagedVersionOf(SkillsStore store, String skillId) {
        SkillRule skill = store.byId(skillId).orElseThrow(() ->
                new Usage("no skill with id " + skillId, false));
        if (skill.status() == SkillStatus.ACTIVE) {
            return skill;
        }
        if (skill.status() != SkillStatus.CANDIDATE) {
            throw new Usage("skill " + skillId + " is " + skill.status()
                    + " — only CANDIDATE skills can be staged for gating", false);
        }
        return skill.withStatus(SkillStatus.ACTIVE);
    }

    // ---- gate ---------------------------------------------------------------

    private static int gate(Map<String, String> flags, java.io.PrintStream out,
                            java.io.PrintStream err, Map<String, String> env) throws Exception {
        Path baselineReport = Path.of(required(flags, "--baseline"));
        Path candidateReport = Path.of(required(flags, "--candidate"));
        Path partitionFile = Path.of(required(flags, "--partition"));
        Path skillsFile = Path.of(required(flags, "--skills"));
        Path stagedFile = Path.of(required(flags, "--staged"));
        Path rejectedFile = flags.containsKey("--rejected") ? Path.of(flags.get("--rejected")) : null;
        int minWins = Integer.parseInt(flags.getOrDefault("--min-wins", "1"));

        CorpusPartition partitioned = CorpusPartition.load(partitionFile);
        EvalReport baseline = EvalReport.fromJson(Files.readString(baselineReport));
        EvalReport candidate = EvalReport.fromJson(Files.readString(candidateReport));

        // DOUBLE-BLIND, door two: holdout ids must never reach the merge decision
        for (EvalReport report : List.of(baseline, candidate)) {
            for (var result : report.results()) {
                partitioned.enforceBlind(result.caseId(), "gate (report " + report.subject() + ")");
            }
        }
        // the gate must actually be the proposal set, not some arbitrary slice
        Set<String> proposalIds = partitioned.proposalIds();
        Set<String> gated = baseline.results().stream()
                .map(com.cifa.eval.EvalResult::caseId)
                .collect(Collectors.toSet());
        if (!proposalIds.containsAll(gated)) {
            throw new Usage("gate reports cover case ids outside the proposal set "
                    + "— regate over the proposal partition", false);
        }

        PairedGatekeeper.GateVerdict verdict = PairedGatekeeper.gate(baseline, candidate, minWins);
        out.println("gate: " + verdict.summary());
        out.println("  reason: " + verdict.reason());
        if (verdict.promote()) {
            out.println("  improvements: " + verdict.improvementCases());
        } else if (!verdict.regressionCases().isEmpty()) {
            out.println("  regressions: " + verdict.regressionCases());
        }

        // Apply the verdict to the staged candidates in the MAIN store.
        SkillsStore store = new SkillsStore(skillsFile,
                new SkillAuditLog(skillsFile.resolveSibling("skill-audit.jsonl")));
        SkillsStore staged = new SkillsStore(stagedFile,
                new SkillAuditLog(stagedFile.resolveSibling("skill-audit-staged.jsonl")));
        String datasetVersion = flags.getOrDefault("--dataset-version", "gate-" + verdict.wins() + "w" + verdict.losses() + "l");
        String approver = env.get("EVOLUTION_APPROVAL");
        RejectedPathStore rejected = rejectedFile == null ? null : new RejectedPathStore(rejectedFile);

        // staged = active set + candidates flipped ACTIVE → the staged-only ids
        // are exactly the candidates this verdict is about
        Set<String> mainActiveIds = store.all().stream()
                .filter(skill -> skill.status() == SkillStatus.ACTIVE)
                .map(SkillRule::id)
                .collect(Collectors.toSet());
        List<SkillRule> candidates = staged.all().stream()
                .filter(skill -> !mainActiveIds.contains(skill.id()))
                .toList();
        if (candidates.isEmpty()) {
            throw new Usage("staged file carries no candidates beyond the main store's active set "
                    + "— nothing for this verdict to apply to", false);
        }

        for (SkillRule candidateSkill : candidates) {
            store.recordValidation(candidateSkill.id(), datasetVersion,
                    verdict.wins(), verdict.losses(), "gatekeeper",
                    baselineReport.getFileName() + " vs " + candidateReport.getFileName().toString());
            if (verdict.promote()) {
                store.promote(candidateSkill.id(), approver,
                        verdict.summary(), candidateReport.getFileName().toString());
                out.println("  promoted: " + candidateSkill.id() + " [" + candidateSkill.kind() + "]");
            } else {
                store.reject(candidateSkill.id(), verdict.summary(), "gatekeeper",
                        candidateReport.getFileName().toString());
                if (rejected != null) {
                    rejected.append(new RejectedPathStore.RejectedPath(
                            String.join(",", candidateSkill.symptomKeywords()),
                            candidateSkill.kind() == SkillKind.ROUTING
                                    ? "route " + candidateSkill.routingTools()
                                    : candidateSkill.instruction(),
                            "gate rejected: " + verdict.summary() + " — " + verdict.reason(),
                            candidateReport.getFileName().toString(), datasetVersion, 1));
                }
                out.println("  rejected (tombstoned + negative knowledge): " + candidateSkill.id());
            }
        }
        if (verdict.promote() && candidates.stream().anyMatch(skill -> skill.kind() == SkillKind.ROUTING)
                && (approver == null || approver.isBlank())) {
            err.println("WARNING: PROMOTE verdict applied, but no EVOLUTION_APPROVAL was set —");
            err.println("ROUTING promotions without a named approver will have been refused by the store.");
        }
        return 0;
    }

    // ---- arbitrate ------------------------------------------------------------

    private static int arbitrate(Map<String, String> flags, java.io.PrintStream out) throws IOException {
        Path skillsFile = Path.of(required(flags, "--skills"));
        String id = required(flags, "--id");
        String decision = required(flags, "--decision");
        String actor = flags.getOrDefault("--actor", "cifa");
        String reason = flags.getOrDefault("--reason", "human arbitration");

        SkillsStore store = new SkillsStore(skillsFile,
                new SkillAuditLog(skillsFile.resolveSibling("skill-audit.jsonl")));
        SkillRule skill = store.byId(id).orElseThrow(() -> new Usage("no skill with id " + id, false));
        if (skill.status() != SkillStatus.CONFLICTED) {
            throw new Usage("skill " + id + " is " + skill.status() + " — arbitrate targets CONFLICTED candidates", false);
        }
        switch (decision) {
            case "promote-new" -> {
                // find the ACTIVE rule this candidate conflicts with; it yields:
                // superseded by the arbitrated winner (lineage link, not deletion)
                ConflictDetector.Verdict conflict = ConflictDetector.classify(skill, store.active());
                if (conflict.classification() == ConflictDetector.Classification.CONFLICT) {
                    store.supersede(conflict.against().id(), id, actor, "lost arbitration: " + reason, "arbitration");
                    out.println("superseded old rule: " + conflict.against().id());
                }
                SkillRule promoted = store.promote(id, flags.get("--approver"), "arbitration: " + reason,
                        "arbitration");
                out.println("promoted: " + promoted.id());
            }
            case "archive-new" -> {
                SkillRule archived = store.reject(id, "arbitration: " + reason, actor, "arbitration");
                out.println("archived (old rule keeps governing): " + archived.id());
            }
            default -> throw new Usage("--decision must be promote-new|archive-new", true);
        }
        return 0;
    }

    // ---- decay ------------------------------------------------------------

    private static int decay(Map<String, String> flags, java.io.PrintStream out) throws IOException {
        Path skillsFile = Path.of(required(flags, "--skills"));
        Path usageFile = Path.of(required(flags, "--usage"));
        double threshold = Double.parseDouble(flags.getOrDefault("--threshold", String.valueOf(DecayEngine.DEFAULT_THRESHOLD)));
        long halfLifeDays = Long.parseLong(flags.getOrDefault("--half-life-days", String.valueOf(DecayEngine.DEFAULT_HALF_LIFE_DAYS)));
        int minStore = Integer.parseInt(flags.getOrDefault("--min-store", String.valueOf(DecayEngine.DEFAULT_MIN_STORE)));
        long now = flags.containsKey("--now-millis")
                ? Long.parseLong(flags.get("--now-millis")) : System.currentTimeMillis();

        SkillsStore store = new SkillsStore(skillsFile,
                new SkillAuditLog(skillsFile.resolveSibling("skill-audit.jsonl")));
        SkillUsageStore usage = new SkillUsageStore(usageFile);
        List<DecayEngine.Decision> decisions = DecayEngine.run(store, usage, threshold,
                halfLifeDays, minStore, now, "decay", "decay-run");
        decisions.forEach(decision -> out.println(
                decision.skillId() + " score=" + String.format(java.util.Locale.ROOT, "%.2f", decision.score())
                        + (decision.markedStale() ? " → STALE" : " (active)")));
        long stale = decisions.stream().filter(DecayEngine.Decision::markedStale).count();
        out.println("decay: " + stale + "/" + decisions.size() + " marked stale (tombstones intact, reactivate available)");
        return 0;
    }

    // ---- snapshot / rollback ------------------------------------------------

    private static int snapshot(Map<String, String> flags, java.io.PrintStream out) throws IOException {
        Path skillsFile = Path.of(required(flags, "--skills"));
        Path dir = Path.of(flags.getOrDefault("--dir", "target/evolution"));
        EvolutionSnapshots snapshots = new EvolutionSnapshots(dir.resolve("evolution-snapshots.jsonl"),
                dir, new SkillAuditLog(skillsFile.resolveSibling("skill-audit.jsonl")));
        EvolutionSnapshots.Snapshot snapshot = snapshots.snapshot(skillsFile,
                required(flags, "--eval-report"),
                flags.getOrDefault("--dataset-version", ""),
                flags.getOrDefault("--note", ""), flags.getOrDefault("--actor", "cifa"));
        out.println("snapshot seq=" + snapshot.seq() + " hash=" + snapshot.skillsHash()
                + " → " + dir.toAbsolutePath());
        return 0;
    }

    private static int rollback(Map<String, String> flags, java.io.PrintStream out) throws IOException {
        Path skillsFile = Path.of(required(flags, "--skills"));
        long seq = Long.parseLong(required(flags, "--seq"));
        Path dir = Path.of(flags.getOrDefault("--dir", "target/evolution"));
        EvolutionSnapshots snapshots = new EvolutionSnapshots(dir.resolve("evolution-snapshots.jsonl"),
                dir, new SkillAuditLog(skillsFile.resolveSibling("skill-audit.jsonl")));
        byte[] before = Files.readAllBytes(skillsFile);
        String hash = snapshots.rollback(skillsFile, seq, flags.getOrDefault("--actor", "cifa"));
        byte[] after = Files.readAllBytes(skillsFile);
        byte[] blob = Files.readAllBytes(dir.resolve("skills-" + hash + ".json"));
        out.println("rollback to snapshot " + seq + " (hash " + hash + ")");
        out.println("behavior changed by this rollback: " + !java.util.Arrays.equals(before, after));
        out.println("restored bytes identical to snapshot blob: " + java.util.Arrays.equals(after, blob));
        return 0;
    }

    // ---- list / show --------------------------------------------------------

    private static int list(Map<String, String> flags, java.io.PrintStream out) throws IOException {
        SkillsStore store = new SkillsStore(Path.of(required(flags, "--skills")),
                new SkillAuditLog(Path.of(required(flags, "--skills")).resolveSibling("skill-audit.jsonl")));
        String statusFilter = flags.get("--status");
        for (SkillRule skill : store.all()) {
            if (statusFilter != null && !skill.status().name().equalsIgnoreCase(statusFilter)) {
                continue;
            }
            out.println(skill.id() + " [" + skill.status() + "] " + skill.kind() + " " + skill.category()
                    + " " + skill.name()
                    + (skill.validatedOnDataset().isEmpty() ? ""
                            : " (gate " + skill.wins() + "w/" + skill.losses() + "l on " + skill.validatedOnDataset() + ")")
                    + (skill.rejectionReason().isEmpty() ? "" : " ✗ " + skill.rejectionReason()));
        }
        out.println("total: " + store.size() + " skills, " + store.active().size() + " active");
        return 0;
    }

    private static int show(Map<String, String> flags, java.io.PrintStream out) throws IOException {
        SkillsStore store = new SkillsStore(Path.of(required(flags, "--skills")),
                new SkillAuditLog(Path.of(required(flags, "--skills")).resolveSibling("skill-audit.jsonl")));
        SkillRule skill = store.byId(required(flags, "--id"))
                .orElseThrow(() -> new Usage("no skill with id " + flags.get("--id"), false));
        out.println(skill.toJson().toString(2));
        return 0;
    }

    // ---- inject (Tier-1 corpus harness) --------------------------------------

    private static int inject(Map<String, String> flags, java.io.PrintStream out,
                              java.io.PrintStream err) throws IOException, InterruptedException {
        Path repo = Path.of(required(flags, "--repo"));
        String fault = required(flags, "--fault");
        String value = flags.getOrDefault("--value", "");
        Path out_ = Path.of(flags.getOrDefault("--out", "target/injected-cases"));
        boolean dryRun = flags.containsKey("--dry-run");
        if (!Files.isDirectory(repo)) {
            throw new Usage("--repo must be a directory (a real, building project)", false);
        }
        GoldenCaseInjector injector = GoldenCaseInjector.forFault(fault);
        GoldenCaseInjector.Result result = injector.inject(repo, value, out_, dryRun);
        out.println("injected fault '" + fault + "' → " + result.caseDir());
        out.println("  mutated files: " + result.mutatedFiles());
        out.println("  gold label (free, from the injection): " + result.goldCategory() + " / " + result.goldKeywords());
        if (dryRun) {
            out.println("  dry-run: build NOT executed — run without --dry-run to capture a real build log");
        } else {
            out.println("  build log: " + result.buildLog());
        }
        out.println("  next: review the case skeleton, then move it under fixtures/eval/<id>/case.json");
        return 0;
    }

    // ---- helpers -------------------------------------------------------------

    private static String required(Map<String, String> flags, String name) {
        String value = flags.get(name);
        if (value == null || value.isBlank()) {
            throw new Usage(name + " is required", true);
        }
        return value;
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new Usage(flag + " expects a value", false);
        }
        return args[index];
    }
}
