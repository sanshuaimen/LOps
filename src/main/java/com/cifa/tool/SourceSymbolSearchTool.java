package com.cifa.tool;

import com.cifa.evidence.Evidence;
import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.llm.ToolSchema;
import com.cifa.security.RepositoryPathPolicy;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Answers "where is this class/method/symbol defined, who calls it, and is
 * there a test for it?" via repo-wide search. Independently invokable — it
 * must not require source_location or git_history to run first.
 */
public final class SourceSymbolSearchTool implements InvestigationTool {

    public static final String NAME = "source_symbol_search";
    private static final int MAX_SCANNED_FILES = 2000;
    private static final int MAX_FILE_CHARS = 1_000_000;
    private static final int MAX_MATCH_LINES = 200;
    private static final int MAX_WALK_DEPTH = 12;
    // Total bytes actually read across the scan; caps the worst case at a sane
    // budget instead of 2000 files x 1MB.
    private static final long DEFAULT_TOTAL_BUDGET_BYTES = 64L * 1024 * 1024;
    private static final List<String> SCANNED_EXTENSIONS = List.of(".java", ".kt", ".scala", ".groovy", ".xml", ".properties", ".gradle");
    private static final List<String> SKIPPED_DIRS = List.of(".git", "target", "build", "node_modules", ".idea");

    private final EvidenceCatalog catalog;
    private final RepositoryPathPolicy paths;
    private final long totalBudgetBytes;

    public SourceSymbolSearchTool(EvidenceCatalog catalog, RepositoryPathPolicy paths) {
        this(catalog, paths, DEFAULT_TOTAL_BUDGET_BYTES);
    }

    SourceSymbolSearchTool(EvidenceCatalog catalog, RepositoryPathPolicy paths, long totalBudgetBytes) {
        this.catalog = catalog;
        this.paths = paths;
        this.totalBudgetBytes = totalBudgetBytes;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Search the whole repository for a class, method or symbol: where it is defined, "
                + "where it is used, and whether matching files live under a test source tree. "
                + "Can be called on its own, without any prior tool.";
    }

    @Override
    public ToolSchema schema() {
        JSONObject properties = new JSONObject()
                .put("symbol", new JSONObject()
                        .put("type", "string")
                        .put("description", "Class, method or symbol name to search for (word match)"));
        JSONObject parameters = new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", List.of("symbol"));
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
            return ToolResult.failed("repository scan failed: " + e.getMessage());
        }
    }

    private ToolResult doExecute(Map<String, Object> arguments, long started) throws IOException {
        String symbol = ToolArgs.requireString(arguments, "symbol");
        if (!symbol.matches("[A-Za-z0-9_$.-]{2,}")) {
            return ToolResult.invalidArgument("symbol must be a plain identifier, got: " + symbol);
        }

        Pattern word = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");
        Pattern declaration = Pattern.compile(
                "\\b(class|interface|enum|record)\\s+" + Pattern.quote(symbol) + "\\b");

        List<String> matches = new ArrayList<>();
        int totalMatches = 0;
        int testMatches = 0;
        List<String> definitions = new ArrayList<>();
        int scannedFiles = 0;
        long bytesScanned = 0;
        boolean truncated = false;

        try (Stream<Path> walk = Files.walk(paths.root(), MAX_WALK_DEPTH)) {
            List<Path> candidates = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> !isSkipped(paths.root().relativize(path)))
                    .filter(path -> hasScannedExtension(path.getFileName().toString()))
                    .limit(MAX_SCANNED_FILES)
                    .toList();

            for (Path file : candidates) {
                if (totalMatches >= MAX_MATCH_LINES) {
                    truncated = true;
                    break;
                }
                long fileSize;
                String text;
                try {
                    fileSize = Files.size(file);
                    if (fileSize > MAX_FILE_CHARS || bytesScanned + fileSize > totalBudgetBytes) {
                        truncated = true;
                        break;
                    }
                    text = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                bytesScanned += fileSize;
                scannedFiles++;
                boolean isTestPath = isTestPath(paths.relative(file));
                String[] lines = text.split("\n", -1);
                for (int i = 0; i < lines.length && totalMatches < MAX_MATCH_LINES; i++) {
                    Matcher matcher = word.matcher(lines[i]);
                    if (!matcher.find()) {
                        continue;
                    }
                    totalMatches++;
                    if (isTestPath) {
                        testMatches++;
                    }
                    if (declaration.matcher(lines[i]).find()) {
                        definitions.add(paths.relative(file) + ":" + (i + 1));
                    }
                    matches.add(paths.relative(file) + ":" + (i + 1) + (isTestPath ? " [test] " : " ") + lines[i].strip());
                }
            }
        }

        if (matches.isEmpty()) {
            return ToolResult.noData("no occurrences of symbol '" + symbol + "' in the repository");
        }

        StringBuilder content = new StringBuilder();
        content.append("symbol: ").append(symbol).append('\n');
        content.append("files scanned: ").append(scannedFiles).append('\n');
        content.append("total word matches: ").append(totalMatches)
                .append(truncated ? " (scan stopped early: match cap or byte budget reached)" : "").append('\n');
        content.append("matches in test sources: ").append(testMatches).append('\n');
        if (!definitions.isEmpty()) {
            content.append("declarations:\n");
            definitions.forEach(definition -> content.append("  ").append(definition).append('\n'));
        } else {
            content.append("declarations: none found (symbol may only be referenced)\n");
        }
        content.append("occurrences:\n");
        matches.forEach(match -> content.append("  ").append(match).append('\n'));

        Evidence evidence = catalog.register(EvidenceType.SOURCE, "symbol:" + symbol, content.toString(), NAME);
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return ToolResult.ok(content.toString(), List.of(evidence.id()), elapsed);
    }

    private static boolean isSkipped(Path relativePath) {
        for (Path part : relativePath) {
            if (SKIPPED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasScannedExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return SCANNED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static boolean isTestPath(String relativePath) {
        String normalized = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("src/test/") || normalized.contains("/test/");
    }
}
