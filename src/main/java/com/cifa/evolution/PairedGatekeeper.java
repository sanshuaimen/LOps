package com.cifa.evolution;

import com.cifa.eval.EvalReport;
import com.cifa.eval.EvalResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The evolution plan's admission gate — paired per-case comparison, not an
 * aggregate mean. The plan correction that motivated this class: "mean up,
 * but broke already-solved cases" is the classic regression an aggregate
 * hides; the gate instead pairs baseline vs candidate results ON THE SAME
 * cases and reads a 2×2 win/loss table.
 *
 * <p>Hard rules, in order:</p>
 * <ol>
 *   <li><b>Zero regression on previously-solved cases</b> — any case the
 *       baseline passed and the candidate fails rejects outright. This is the
 *       constraint, not a preference.</li>
 *   <li><b>Net improvement</b> — wins must reach {@code minWins} (default 1);
 *       a candidate that changes behavior without measurably improving
 *       anything does not get to merge.</li>
 * </ol>
 *
 * <p>The aggregate pass-rate delta is computed and reported (auxiliary,
 * RunAggregator-style context) but plays NO role in the decision — the plan
 * explicitly demotes "mean ± σ" to a display metric after the A/B variance
 * lesson.</p>
 *
 * <p>Callers gate on the PROPOSAL set only — the fixed holdout must never
 * reach this decision ({@code CorpusPartition}); multi-run replays should
 * aggregate to strict per-case pass first and gate those.</p>
 */
public final class PairedGatekeeper {

    public static final int DEFAULT_MIN_WINS = 1;

    /** The gate's full verdict: counts, per-case detail, and the reason line
     * that travels into the audit log and (on reject) the tombstone. */
    public record GateVerdict(
            boolean promote,
            int wins,
            int losses,
            int bothPass,
            int bothFail,
            double baselinePassRate,
            double candidatePassRate,
            List<String> improvementCases,
            List<String> regressionCases,
            String reason) {

        public String summary() {
            return (promote ? "PROMOTE" : "REJECT") + " wins=" + wins + " losses=" + losses
                    + " both-pass=" + bothPass + " both-fail=" + bothFail
                    + " pass-rate " + pct(baselinePassRate) + "→" + pct(candidatePassRate);
        }

        private static String pct(double value) {
            return String.format(java.util.Locale.ROOT, "%.1f%%", value * 100.0);
        }
    }

    private PairedGatekeeper() {
    }

    public static GateVerdict gate(EvalReport baseline, EvalReport candidate) {
        return gate(baseline, candidate, DEFAULT_MIN_WINS);
    }

    /**
     * Pairs the two reports case-by-case. Reports must cover the SAME case id
     * set — a gate over mismatched corpora is invalid, not approximate.
     */
    public static GateVerdict gate(EvalReport baseline, EvalReport candidate, int minWins) {
        Map<String, Boolean> baselinePass = passByCase(baseline);
        Map<String, Boolean> candidatePass = passByCase(candidate);
        if (!baselinePass.keySet().equals(candidatePass.keySet())) {
            throw new IllegalArgumentException("paired gate requires identical case sets; baseline has "
                    + baselinePass.size() + " cases, candidate has " + candidatePass.size()
                    + " — run both sides over the same corpus slice");
        }
        if (minWins < 1) {
            throw new IllegalArgumentException("minWins must be >= 1 — a gate that merges on zero wins is theater");
        }

        int wins = 0;
        int losses = 0;
        int bothPass = 0;
        int bothFail = 0;
        List<String> improvements = new ArrayList<>();
        List<String> regressions = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : baselinePass.entrySet()) {
            boolean was = entry.getValue();
            boolean now = candidatePass.get(entry.getKey());
            if (was && now) {
                bothPass++;
            } else if (!was && !now) {
                bothFail++;
            } else if (!was) {
                wins++;
                improvements.add(entry.getKey());
            } else {
                losses++;
                regressions.add(entry.getKey());
            }
        }
        int cases = baselinePass.size();
        double baselineRate = (double) (bothPass + losses) / cases;
        double candidateRate = (double) (bothPass + wins) / cases;

        if (losses > 0) {
            return new GateVerdict(false, wins, losses, bothPass, bothFail, baselineRate, candidateRate,
                    improvements, List.copyOf(regressions),
                    "rejected: " + losses + " regression(s) on previously-solved cases "
                            + regressions + " — the zero-regression constraint is hard");
        }
        if (wins < minWins) {
            return new GateVerdict(false, wins, losses, bothPass, bothFail, baselineRate, candidateRate,
                    improvements, List.copyOf(regressions),
                    "rejected: wins " + wins + " below the required " + minWins
                            + " — a behavior change without measurable improvement does not merge");
        }
        return new GateVerdict(true, wins, losses, bothPass, bothFail, baselineRate, candidateRate,
                List.copyOf(improvements), List.copyOf(regressions),
                "accepted: " + wins + " improvement(s) " + improvements
                        + ", zero regressions, net behavior change is strictly positive");
    }

    private static Map<String, Boolean> passByCase(EvalReport report) {
        Map<String, Boolean> pass = new LinkedHashMap<>();
        for (EvalResult result : report.results()) {
            pass.put(result.caseId(), result.passed());
        }
        return pass;
    }
}
