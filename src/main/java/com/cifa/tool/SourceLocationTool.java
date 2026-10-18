package com.cifa.tool;

import com.cifa.evidence.Evidence;
import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.llm.ToolSchema;
import com.cifa.security.RepositoryPathPolicy;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Answers "what is the code around this path and line?" for precise reads.
 * Paths are normalized and confined to the repository root; global search is
 * not this tool's job. Read-only.
 */
public final class SourceLocationTool implements InvestigationTool {

    public static final String NAME = "source_location";
    static final int MAX_LINES_PER_READ = 200;

    private final EvidenceCatalog catalog;
    private final RepositoryPathPolicy paths;

    public SourceLocationTool(EvidenceCatalog catalog, RepositoryPathPolicy paths) {
        this.catalog = catalog;
        this.paths = paths;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Read a small numbered snippet of a repository source file by path and 1-based line range. "
                + "Use for precise reads once you know where to look; use source_symbol_search for repo-wide search.";
    }

    @Override
    public ToolSchema schema() {
        JSONObject properties = new JSONObject()
                .put("path", new JSONObject()
                        .put("type", "string")
                        .put("description", "Repository-relative file path"))
                .put("startLine", new JSONObject().put("type", "integer").put("description", "1-based start line"))
                .put("endLine", new JSONObject().put("type", "integer").put("description", "1-based end line (inclusive)"));
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
            return ToolResult.failed("unable to read source file: " + e.getMessage());
        }
    }

    private ToolResult doExecute(Map<String, Object> arguments, long started) throws IOException {
        String pathArg = ToolArgs.requireString(arguments, "path");
        int startLine = ToolArgs.optionalInt(arguments, "startLine").orElse(1);
        int endLine = ToolArgs.optionalInt(arguments, "endLine").orElse(startLine + MAX_LINES_PER_READ - 1);
        if (startLine < 1) {
            return ToolResult.invalidArgument("startLine must be >= 1");
        }
        if (endLine < startLine) {
            return ToolResult.invalidArgument("endLine must be >= startLine");
        }
        if (endLine - startLine + 1 > MAX_LINES_PER_READ) {
            return ToolResult.invalidArgument("line range must not exceed " + MAX_LINES_PER_READ + " lines per read");
        }

        // Resolving through the policy rejects absolute paths, escapes and symlink exits.
        java.nio.file.Path file = paths.resolve(pathArg);
        if (!Files.isRegularFile(file)) {
            return ToolResult.failed("not a file inside the repository: " + pathArg);
        }

        List<String> allLines = Files.readAllLines(file);
        int upper = Math.min(endLine, allLines.size());
        if (startLine > allLines.size()) {
            return ToolResult.noData("file has " + allLines.size() + " lines; range " + startLine + "-" + endLine + " is empty");
        }

        String relative = paths.relative(file);
        List<String> snippet = new ArrayList<>();
        for (int index = startLine; index <= upper; index++) {
            snippet.add(index + ": " + allLines.get(index - 1));
        }
        String content = String.join("\n", snippet);
        Evidence evidence = catalog.register(EvidenceType.SOURCE, relative + ":" + startLine + "-" + upper,
                content, NAME, startLine, upper, "");
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return ToolResult.ok(content, List.of(evidence.id()), elapsed);
    }
}
