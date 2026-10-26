# CI/CD Failure Analyzer (MVP)

Analyzes a CI build failure log plus a repo checkout and prints a structured
Markdown root-cause report. The LLM is only the final interpretation layer —
log parsing, classification, and git correlation are deterministic and
independently tested.

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

Add `--format html` or `--format slack` to render an HTML report or a Slack
Block Kit JSON payload instead of the default Markdown.

Add `--post-comment` to post the Markdown report as a GitHub PR comment.
Requires `GITHUB_TOKEN`. The target repo/PR are taken from `--repo-slug`/
`--pr-number` if given, else auto-detected from the GitHub Actions
`GITHUB_REPOSITORY`/`GITHUB_EVENT_PATH` environment variables. If neither
source resolves both values, the report still prints — only the comment
post is skipped, with a message on stderr.

Environment variables:
- `LLM_PROVIDER` — `ollama` (default) or `anthropic`.
- `OLLAMA_MODEL` — defaults to `deepseek-r1:8b`. Used when `LLM_PROVIDER=ollama`.
- `ANTHROPIC_API_KEY` — required when `LLM_PROVIDER=anthropic`. Never logged.
- `ANTHROPIC_MODEL` — defaults to `claude-sonnet-5`. Used when `LLM_PROVIDER=anthropic`.
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
OLLAMA_MODEL: llama3
DEFAULT_FORMAT: html
CONTEXT_CHAR_BUDGET: 3000
```

Precedence for every setting: CLI flag > environment variable > config file >
built-in default.

Secrets (`ANTHROPIC_API_KEY`, `GITHUB_TOKEN`) are read from the environment
only, never from the config file, and `.env`/`config.yaml` are gitignored by
default.

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
6. `llm` — `LLMProvider` interface; `OllamaProvider` (local) or `AnthropicProvider`
   (hosted, selected via `LLM_PROVIDER=anthropic`) parse/validate JSON and flag
   evidence not traceable back to the context (`EvidenceValidator`)
7. `report` — Markdown, HTML, and Slack Block Kit renderers, selected via `--format`
8. `integration` — `GitHubCommentPoster` posts the rendered Markdown report
   as a PR comment via the GitHub REST API (`--post-comment`)
