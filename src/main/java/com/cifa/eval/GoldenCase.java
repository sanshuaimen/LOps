package com.cifa.eval;

import com.cifa.classification.FailureCategory;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public record GoldenCase(
        String id,
        Path logPath,
        Path repoRoot,
        FailureCategory expectedCategory,
        String layer,
        List<String> rootCauseKeywords,
        List<String> evidenceLocators,
        boolean shouldBeUnknown,
        List<String> forbiddenConclusions,
        List<String> requiredTools,
        List<String> relevantTools
) {

    /** Corpus layers: accept is the path-sensitive acceptance set, the rest group
     * regression / robustness / security material for layered reporting. */
    private static final List<String> LAYERS = List.of("accept", "regression", "robustness", "security");

    public static List<String> knownLayers() {
        return LAYERS;
    }

    public GoldenCase {
        rootCauseKeywords = List.copyOf(rootCauseKeywords);
        evidenceLocators = List.copyOf(evidenceLocators);
        forbiddenConclusions = List.copyOf(forbiddenConclusions);
        requiredTools = List.copyOf(requiredTools);
        relevantTools = List.copyOf(relevantTools);
    }

    public static GoldenCase load(Path caseFile) throws IOException {
        JSONObject json = new JSONObject(Files.readString(caseFile));
        Path base = caseFile.getParent();
        String log = json.optString("log", "build.log");
        String repo = json.optString("repo", "repo");
        String layer = json.getString("layer");
        if (!LAYERS.contains(layer)) {
            throw new IllegalArgumentException("unknown layer '" + layer + "' in " + caseFile
                    + "; expected one of " + LAYERS);
        }
        return new GoldenCase(
                json.getString("id"),
                base.resolve(log).normalize(),
                base.resolve(repo).normalize(),
                FailureCategory.valueOf(json.getString("expectedCategory")),
                layer,
                strings(json.optJSONArray("rootCauseKeywords")),
                strings(json.optJSONArray("evidenceLocators")),
                json.optBoolean("shouldBeUnknown", false),
                strings(json.optJSONArray("forbiddenConclusions")),
                strings(json.optJSONArray("requiredTools")),
                strings(json.optJSONArray("relevantTools"))
        );
    }

    public static List<GoldenCase> loadDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(directory, 3)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("case.json"))
                    .sorted(Comparator.naturalOrder())
                    .map(path -> {
                        try {
                            return load(path);
                        } catch (IOException e) {
                            throw new GoldenCaseLoadException(path, e);
                        }
                    })
                    .toList();
        } catch (GoldenCaseLoadException e) {
            throw e;
        }
    }

    /** Same corpus walk, scoped to one layer — used by layered runs and per-layer floors. */
    public static List<GoldenCase> loadDirectory(Path directory, String layer) throws IOException {
        if (!knownLayers().contains(layer)) {
            throw new IllegalArgumentException("unknown layer '" + layer + "'; expected one of " + LAYERS);
        }
        return loadDirectory(directory).stream()
                .filter(candidate -> candidate.layer().equals(layer))
                .toList();
    }

    private static List<String> strings(JSONArray array) {
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            values.add(array.getString(i));
        }
        return values;
    }

    private static final class GoldenCaseLoadException extends RuntimeException {
        private GoldenCaseLoadException(Path path, IOException cause) {
            super("Unable to load golden case " + path, cause);
        }
    }
}
