# CI/CD Failure Analyzer

Analyzes a CI build failure log plus a repo checkout and prints a structured
Markdown root-cause report. The LLM is only the final interpretation layer —
log parsing, classification, and git correlation are deterministic and
independently tested.

Two diagnosis engines share the deterministic pipeline:

- **baseline** (default) — single LLM call over the parsed context. Cheap,
  always available.
- **agent** (`--mode agent`) — a controlled investigation loop: each round the
  model may do exactly one of call a whitelisted tool, submit a diagnosis, or
  declare unknown. Budgets, evidence authenticity, and sufficiency floors are
  deterministic code; the model only chooses among gated options.

The agent path never fails silently: on agent failure or transport breakdown
a deterministic fallback chain degrades to the baseline, then to a rules-only
classifier that honestly reports "no root cause" rather than guessing.

## Setup

Requires Java 17+, Maven, and [Ollama](https://ollama.com) running locally.

```bash
ollama pull deepseek-r1:8b   # one-time; matches the MVP's default model
mvn package
```

## Usage

```bash
java -jar target/analyzer.jar --log path/to/build.log --repo path/to/repo
```

Add `--mode agent` to run the investigation loop instead of the baseline.
Add `--judge` (agent mode only) to route submitted conclusions through a
semantic-review judge before acceptance. Add `--trace <file>` to record the
round-by-round investigation as JSONL (defaults to `agent-trace-<taskId>.jsonl`).

Add `--format html` or `--format slack` to render an HTML report or a Slack
Block Kit JSON payload instead of the default Markdown.

Add `--post-comment` to post the Markdown report as a GitHub PR comment.
Requires `GITHUB_TOKEN`. The target repo/PR are taken from `--repo-slug`/
`--pr-number` if given, else auto-detected from the GitHub Actions
`GITHUB_REPOSITORY`/`GITHUB_EVENT_PATH` environment variables. If neither
source resolves both values, the report still prints — only the comment
post is skipped, with a message on stderr.

Environment variables:
- `LLM_PROVIDER` — `ollama` (default), `anthropic`, `deepseek`, or `fake`.
- `OLLAMA_MODEL` — defaults to `deepseek-r1:8b`. Used when `LLM_PROVIDER=ollama`.
- `ANTHROPIC_API_KEY` — required when `LLM_PROVIDER=anthropic`. Never logged.
- `ANTHROPIC_MODEL` — defaults to `claude-sonnet-5`. Used when `LLM_PROVIDER=anthropic`.
- `DEEPSEEK_API_KEY` — required when `LLM_PROVIDER=deepseek`. Never logged.
- `DEEPSEEK_MODEL` — used when `LLM_PROVIDER=deepseek`.
- `FAKE_SCRIPT` — path to a recorded decision script for `LLM_PROVIDER=fake`
  (offline runs with zero network access).
- `DIAGNOSIS_MODE` — `baseline` (default) or `agent`. Overridden by `--mode`.
- `JUDGE_ENABLED` — `true` enables the judge in agent mode. Overridden by `--judge`.
- `AGENT_MODEL` — model name stamped into agent traces/reports.
- `GITHUB_TOKEN` — required for `--post-comment`. Never logged.
- `GITHUB_REPOSITORY`, `GITHUB_EVENT_PATH` — set automatically in GitHub Actions;
  used to auto-detect the repo slug and PR number for `--post-comment` when
  `--repo-slug`/`--pr-number` aren't given.
- `DEFAULT_FORMAT` — `markdown` (default), `html`, or `slack`. Overridden by `--format`.
- `CONTEXT_CHAR_BUDGET` — max characters for the source snippet/diff hunk sent to the LLM.

## Configuration file

Instead of (or in addition to) environment variables, drop a `config.yaml`
or `.env` file in the working directory:

```yaml
LLM_PROVIDER: anthropic
DIAGNOSIS_MODE: agent
OLLAMA_MODEL: llama3
DEFAULT_FORMAT: html
CONTEXT_CHAR_BUDGET: 3000
```

Precedence for every setting: CLI flag > environment variable > config file >
built-in default.

Secrets (`ANTHROPIC_API_KEY`, `DEEPSEEK_API_KEY`, `GITHUB_TOKEN`) are read
from the environment only, never from the config file, and `.env`/`config.yaml`
are gitignored by default.

## Agent mode

`--mode agent` runs `InvestigationAgentLoop` behind
`FallbackDiagnosisOrchestrator`:

- **Whitelisted tools only** — read-only repo access (file read, grep, git log
  / diff, test-report inspection). No shell, no writes.
- **Deterministic gates** — budget (rounds / tokens / time), evidence
  authenticity (every cited evidence ID must resolve and match the context),
  and a sufficiency floor are enforced in code, never by the model.
- **Fallback chain** — agent `MODEL_FAILURE` or transport errors degrade to
  the baseline (same provider), then to the rules-only classifier. Every
  degraded output carries a stage + reason stamp and a `fallback` trace
  event; `InterruptedException` never degrades (cancellation is not swallowed).
- **Honest unknowns** — insufficient evidence stops with
  `INSUFFICIENT_EVIDENCE` and an `unknown` diagnosis that carries no
  suggested action.

### Judge (semantic review, `--judge`)

An optional third layer that reviews a submitted conclusion *after* the
deterministic gates accept it:

- `UNSUPPORTED` sends the submission back **once** for revision (the model is
  told why and may revise, re-cite, or declare unknown).
- A second rejection downgrades to an honest `UNKNOWN` diagnosis stamped
  `JUDGE_UNSUPPORTED` — kept distinct from `INSUFFICIENT_EVIDENCE` so metrics
  never conflate "agent found nothing" with "review overturned a claim".
- Accepted conclusions carry a `judgeVerdict` stamp — a stamp only, never a
  recomputed confidence.
- Judge malfunction degrades internally (`UNAVAILABLE`) and never blocks a
  diagnosis nor triggers the fallback chain.

## Evaluation harness

`EvalCommand` runs the golden corpus (`fixtures/eval`, 62 cases across
accept / regression / robustness / security layers) through baseline + agent
and writes versioned comparison reports. CI never runs this against real
models — scoring there is fully deterministic.

```bash
java -cp target/analyzer.jar com.cifa.cli.EvalCommand --provider fake --layer accept
```

- `--provider fake|ollama|anthropic|deepseek`, `--model`, `--runs N`,
  `--layer accept|regression|robustness|security|all`, `--out <dir>`.
- `--provider fake` with `--fake-script` replays a recorded decision script —
  the full agent path with zero network.
- `--memory <file>` enables long-term failure memory retrieval A/B;
  the CLI owns store + retrieval and excludes the querying case's own id.
- `--judge` A/Bs the in-loop semantic review (the judge is derived from each
  case's own provider, so a scripted fake serves both from one script).
- `--judge-grader` lets an LLM re-grade keyword misses at scoring time (hits
  always pass unchanged, so the grader can rescue differently-worded correct
  answers but never overturn a deterministic pass). Both flags are no-ops
  with `--provider fake`: CI scoring stays deterministic.

## Failure memory

`MemoryCommand` maintains the cross-run notebook of past failures
(`failure-memory.json`, gitignored — it is a runtime asset):

```bash
java -cp target/analyzer.jar com.cifa.cli.MemoryCommand add --id ... --case-id ... --layer regression --category ... --root-cause ...
java -cp target/analyzer.jar com.cifa.cli.MemoryCommand list
java -cp target/analyzer.jar com.cifa.cli.MemoryCommand stats
java -cp target/analyzer.jar com.cifa.cli.MemoryCommand query --log path/to/build.log
```

Layer gate: only `regression`/`robustness` cases may seed the memory; the
`accept` layer is the held-out exam and is rejected here and in the store.

### Distilling diagnoses into semantic memory

The notebook's own A/B verdict was "episodic case transcripts don't raise
accuracy — entry information density beats retrieval mechanics". `distill`
is the answer: it turns ONE completed diagnosis into a single de-case-ified
semantic lesson (generalized failure pattern + actionable remediation), then
consolidates it against similar stored entries before anything is written.

```bash
java -cp target/analyzer.jar com.cifa.cli.MemoryCommand distill \
  --trace agent-trace-<taskId>.jsonl --task-id <taskId> [--log build.log] \
  --provider fake|ollama|anthropic|deepseek [--model m]
```

Two LLM steps, each behind a deterministic gate: **extract** (one lesson per
diagnosis; the recall signature comes from the build log when `--log` is
given, never from the model) and **consolidate** (`add` / `update` / `noop`
against the BM25-similar entries already stored — the notebook never grows
unbounded duplicates). A model that returns garbage — or nothing worth
storing — skips with a reason and the notebook is left untouched; a broken
distiller can never corrupt it. Entries are stamped layer `distilled` and
source `distilled:memory-distill-v1`; ids are content-addressed, so identical
lessons deduplicate by construction.

## HTTP server

A Spring Boot shell (`DiagnosisServerApplication`) exposes the pipeline over
HTTP with persisted task state (`POST /api/diagnoses`, `GET
/api/diagnoses/{id}`, `POST /api/diagnoses/{id}/cancel`). Everything under
`web`/`persistence`/`config`/`app` may depend on Spring; all other packages
are the framework-free core (enforced by ArchitectureTest).

## Try it with the bundled fixtures

```bash
./fixtures/create-sample-repo.sh
java -jar target/analyzer.jar --log fixtures/logs/github-actions-npe.log --repo fixtures/sample-repo
java -jar target/analyzer.jar --log fixtures/logs/jenkins-timeout.log --repo fixtures/sample-repo
java -jar target/analyzer.jar --log fixtures/logs/dependency-resolution-failure.log --repo fixtures/sample-repo
```

The first is a Java/Maven NullPointerException (`LOGIC_ERROR`) with a real git
correlation; the second is a socket timeout (`INFRA_FLAKY`), which skips git
correlation entirely and renders a "low confidence" banner instead of guessing
a code-level cause.
The third is a Maven dependency-resolution failure (`DEPENDENCY_BUILD`), which
still attempts git correlation (only `INFRA_FLAKY` skips it) but finds no
stack frame to correlate against, since the failure has no code-level frame.

```bash
./fixtures/create-multi-module-repo.sh
java -jar target/analyzer.jar --log fixtures/logs/multi-module-npe.log --repo fixtures/multi-module-repo
```

This one is a two-Maven-module repo; the failure is in `module-b`, and
correlation resolves it by searching each module's source root rather than
assuming a flat layout.

## Architecture

One package per pipeline stage, each a typed record contract in/out:

1. `ingestion` — strip ANSI codes, GH Actions/Jenkins noise, timestamps
2. `parsing` — Surefire/Failsafe XML reports (`target/**/surefire-reports/TEST-*.xml`)
   when present under `--repo`, else regex extraction (Java primary,
   Python/Node/generic fallback) on the console log text
3. `classification` — rule-table lookup, not the LLM (`LOGIC_ERROR` /
   `INFRA_FLAKY` / `DEPENDENCY_BUILD` / `UNKNOWN`)
4. `correlation` — resolves the first project-owned stack frame to a file,
   searching every Maven/Gradle module source root (falling back to a
   repo-wide search, then "source file could not be located" if still
   unresolved), runs `git log -L` scoped to that line range (skipped for
   `INFRA_FLAKY`)
5. `context` — builds a char-budget-capped `AnalysisContext`, logs exclusions
6. `llm` — `LLMProvider` interface; `OllamaProvider`, `AnthropicProvider`,
   `DeepSeekProvider`, or the offline `FakeLLMProvider` parse/validate JSON
   and flag evidence not traceable back to the context (`EvidenceValidator`)
7. `report` — Markdown, HTML, and Slack Block Kit renderers, selected via `--format`
8. `integration` — `GitHubCommentPoster` posts the rendered Markdown report
   as a PR comment via the GitHub REST API (`--post-comment`)
9. `agent` — the controlled investigation loop (`InvestigationAgentLoop`,
   tool whitelist, budgets, stop reasons, optional `JudgmentAgent`), wrapped
   by `service.FallbackDiagnosisOrchestrator`
10. `task` — `TaskStateMachine` mapping stop reasons to task statuses
    (`SUCCEEDED` / honest `UNKNOWN` / `FAILED` / `CANCELLED`)
11. `eval` — golden-corpus harness (`EvalRunner`, layered fixtures, versioned
    comparison reports, optional `JudgeGrader`)
12. `memory` — failure-memory store + retrieval (signature-based top-k)
13. `trace` — JSONL trace recorder for every agent round and gate decision
14. `evolution` — self-evolution & data-flywheel surface (skills store, paired
    gate, double-blind holdout partition, harvest pipeline, decay, snapshots)
15. `web`/`persistence` — Spring Boot HTTP shell and task persistence

## Self-evolution & data flywheel (`evolution` command)

The system closes the loop between *running* (traces), *measuring* (golden
corpus), and *changing* (behavior assets). Every behavior change is a
declarative, diffable data file — `skills.json` — so merging is a file
change, rollback is a byte restore, and the audit trail is append-only JSONL.

The loop, end to end (all offline; the golden corpus is the only judge):

```
run diagnosis (live or eval)  →  trace JSONL + judge verdict
        │  harvest        (double-blind: holdout-sourced traces refused at the door)
        ▼
memory entries (错题本)  +  skill candidates (CANDIDATE | CONFLICTED-quarantined)
        │  stage          (candidate flips ACTIVE on a staged copy)
        ▼
eval twice (baseline skills vs staged skills, proposal set only)
        │  gate           (paired win/loss: zero regressions on solved cases is
        │                  a HARD constraint; holdout ids in reports refuse loudly)
        ▼
PROMOTE (routing-kind needs EVOLUTION_APPROVAL)  |  REJECT (tombstone + negative-knowledge ledger)
        │  snapshot       (skills.json bytes ↔ SHA-256 ↔ eval report ↔ dataset version)
        ▼
rollback anytime — byte-identical restore;  decay — survival-scored dormancy
```

Quick tour:

```bash
# 1. split the corpus once: fixed holdout (~30%, double-blind) + rotating proposal set
java -cp target/analyzer.jar com.cifa.cli.EvolutionCommand partition

# 2. reflow completed traces into memory + skill candidates (fake provider = free)
java -cp target/analyzer.jar com.cifa.cli.EvolutionCommand harvest \
  --traces 'agent-trace-*.jsonl' --skills skills.json --memory failure-memory.json \
  --rejected rejected-paths.json --partition partition.json --provider fake

# 3. A/B a candidate: stage it, eval both sides, gate the reports
java -cp target/analyzer.jar com.cifa.cli.EvolutionCommand stage \
  --skills skills.json --id skill-abc123 --out skills-staged.json
java -jar target/analyzer.jar --log ... --repo ... --mode agent --skills skills.json   # live: skills inject deterministically
#   eval --skills skills-staged.json ... vs eval --skills skills.json ...

# 4. apply the verdict (promote or tombstone), snapshot, decay, rollback
EVOLUTION_APPROVAL=alice java -cp target/analyzer.jar com.cifa.cli.EvolutionCommand gate ...
java -cp target/analyzer.jar com.cifa.cli.EvolutionCommand snapshot --skills skills.json --eval-report ...
java -cp target/analyzer.jar com.cifa.cli.EvolutionCommand decay --skills skills.json --usage skill-usage.json
java -cp target/analyzer.jar com.cifa.cli.EvolutionCommand rollback --seq 1 --skills skills.json
```

Design invariants, each pinned by a test:

- **State machine**: `CANDIDATE → ACTIVE → (STALE | SUPERSEDED) → ARCHIVED`,
  conflicts quarantine as `CONFLICTED` (the active rule keeps governing until a
  human arbitrates); ARCHIVED is a tombstone — never deleted, never resurrected.
- **Double-blind holdout**: the fixed holdout slice never reaches the Proposer's
  inputs (harvest) and never reaches merge decisions (gate) — enforced twice, loudly.
- **Paired gate, not aggregate mean**: `wins ≥ minWins` AND `zero regressions on
  previously-solved cases`; pass-rate deltas are display-only (the A/B variance lesson).
- **Routing skills live in the deterministic layer**: the router matches in plain
  code (category + symptom keywords), the model never chooses which skill applies,
  and usage hits feed the decay score (live signal dominates eval signal).
- **No mutation without an audit line**; ROUTING promotions need a named approver;
  decay refuses a library under `--min-store` — governance theater is declined.
- **Isolation, mechanically**: ArchUnit keeps the eval package away from both
  stores' write paths (memory AND skills).

Growing the corpus (data flywheel Tier 1): inject a known fault into a copy of a
real Maven project, build the mutated copy for a REAL log, get a golden-case
skeleton whose gold label falls out of the injection for free:

```bash
java -cp target/analyzer.jar com.cifa.cli.EvolutionCommand inject \
  --repo /path/to/real-maven-project --fault dependency-version --dry-run
# review target/injected-*/case.json, then move under fixtures/eval/<id>/ and re-partition
```
