package com.cifa.tool;

import com.cifa.evidence.Evidence;
import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.git.GitCommandExecutor;
import com.cifa.git.GitCommandResult;
import com.cifa.llm.ToolSchema;
import com.cifa.security.RepositoryPathPolicy;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers "which recent changes could have introduced this regression?" via a
 * fixed whitelist of read-only git templates (log / blame / show / diff).
 * No model-supplied git arguments are passed through; every result registers
 * GIT evidence carrying a real commit id.
 */
public final class GitHistoryTool implements InvestigationTool {

    public static final String NAME = "git_history";
    private static final List<String> OPERATIONS = List.of("log", "blame", "show", "diff");
    private static final Pattern COMMIT_LINE = Pattern.compile("(?m)^commit ([0-9a-f]{7,40})");
    private static final Pattern BLAME_COMMIT = Pattern.compile("(?m)^([0-9a-f]{7,40})\\s+\\(");
    private static final Pattern DIFF_INDEX = Pattern.compile("index ([0-9a-f]{7,40})\\.\\.");
    private static final int DEFAULT_LOG_LIMIT = 5;
    private static final int MAX_LOG_LIMIT = 100;

    private final EvidenceCatalog catalog;
    private final GitCommandExecutor git;
    private final RepositoryPathPolicy paths;

    public GitHistoryTool(EvidenceCatalog catalog, GitCommandExecutor git) {
        this.catalog = catalog;
        this.git = git;
        this.paths = new RepositoryPathPolicy(git.repoRoot());
    }

    @Override
    public String name() {
        return NAME;
    }

    /** Backs onto git subprocesses (bounded by the executor's own timeout) — the one tool worth gating. */
    @Override
    public boolean supportsTimeout() {
        return true;
    }

    @Override
    public String description() {
        return "Inspect repository history for a file: recent commits (log), per-line attribution (blame), "
                + "a specific commit's change (show), or the diff since a revision (diff). "
                + "Only these fixed read-only operations are available.";
    }

    @Override
    public ToolSchema schema() {
        JSONObject properties = new JSONObject()
                .put("path", new JSONObject()
                        .put("type", "string")
                        .put("description", "Repository-relative file path"))
                .put("operation", new JSONObject()
                        .put("type", "string")
                        .put("enum", OPERATIONS)
                        .put("description", "One of: log, blame, show, diff. Defaults to log."))
                .put("revision", new JSONObject()
                        .put("type", "string")
                        .put("description", "Commit id or ref, required for show and diff"))
                .put("startLine", new JSONObject().put("type", "integer").put("description", "Start line for blame"))
                .put("endLine", new JSONObject().put("type", "integer").put("description", "End line for blame"))
                .put("limit", new JSONObject().put("type", "integer").put("description", "Max commits for log (1-100)"));
        JSONObject parameters = new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", List.of("path"));
        return new ToolSchema(NAME, description(), parameters);
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        long started = System.nanoTime();
        try {
            return doExecute(arguments, started);
        } catch (IllegalArgumentException e) {
            return ToolResult.invalidArgument(e.getMessage());
        } catch (IOException e) {
            return ToolResult.failed("git execution failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.timeout("git execution interrupted");
        }
    }

    private ToolResult doExecute(Map<String, Object> arguments, long started)
            throws IOException, InterruptedException {
        String pathArg = ToolArgs.requireString(arguments, "path");
        String operation = ToolArgs.optionalString(arguments, "operation")
                .map(text -> text.toLowerCase(java.util.Locale.ROOT))
                .orElse("log");
        if (!OPERATIONS.contains(operation)) {
            return ToolResult.invalidArgument("operation must be one of " + OPERATIONS);
        }

        Path file = paths.resolve(pathArg);
        String relative = paths.relative(file);

        GitCommandResult result = switch (operation) {
            case "log" -> {
                int limit = ToolArgs.optionalInt(arguments, "limit").orElse(DEFAULT_LOG_LIMIT);
                if (limit < 1 || limit > MAX_LOG_LIMIT) {
                    throw new IllegalArgumentException("limit must be between 1 and " + MAX_LOG_LIMIT);
                }
                yield git.log(file, limit);
            }
            case "blame" -> {
                int startLine = ToolArgs.requireInt(arguments, "startLine");
                int endLine = ToolArgs.optionalInt(arguments, "endLine").orElse(startLine);
                yield git.blame(file, startLine, endLine);
            }
            case "show" -> git.show(ToolArgs.requireString(arguments, "revision"), file);
            case "diff" -> git.diff(ToolArgs.requireString(arguments, "revision"), file);
            default -> throw new IllegalArgumentException("unsupported operation: " + operation);
        };

        long elapsed = (System.nanoTime() - started) / 1_000_000;
        if (result.timedOut()) {
            return ToolResult.timeout("git " + operation + " timed out after the configured budget");
        }
        if (!result.success()) {
            return ToolResult.failed("git " + operation + " failed: " + summarize(result.stderr(), 300));
        }

        String commit = extractCommit(operation, result.stdout());
        if (commit.isEmpty()) {
            return ToolResult.noData("git " + operation + " returned no commit for " + relative);
        }

        String content = summarize(result.output(), 8000);
        Evidence evidence = catalog.register(EvidenceType.GIT, operation + ":" + relative + "@" + commit,
                content, NAME, Evidence.NO_LINE, Evidence.NO_LINE, commit);
        String message = content + "\n[evidence " + evidence.id() + " commit " + commit + "]";
        return ToolResult.ok(message, List.of(evidence.id()), elapsed);
    }

    private static String extractCommit(String operation, String stdout) {
        Pattern pattern = switch (operation) {
            case "log", "show" -> COMMIT_LINE;
            case "blame" -> BLAME_COMMIT;
            case "diff" -> DIFF_INDEX;
            default -> null;
        };
        if (pattern == null) {
            return "";
        }
        Matcher matcher = pattern.matcher(stdout == null ? "" : stdout);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String summarize(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars) + "\n...[truncated]";
    }
}
