package com.cifa.tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read-only JSON fixture backing {@link FlakyTestHistoryTool} in P0. The file
 * maps test names to recorded runs:
 * <pre>
 * { "com.example.UserServiceTest.findsUser": [
 *     {"build": "b-1", "passed": true},
 *     {"build": "b-2", "passed": false, "rerunPassed": true, "relatedCommit": ""} ] }
 * </pre>
 * Real deployments must swap in a legitimate history data source; the tool's
 * NO_DATA semantics cover "no source configured" and "test unknown".
 */
public final class JsonFlakyHistorySource implements FlakyHistorySource {

    private final JSONObject history;

    public JsonFlakyHistorySource(Path historyFile) throws IOException {
        this.history = new JSONObject(Files.readString(historyFile));
    }

    private JsonFlakyHistorySource(JSONObject history) {
        this.history = history;
    }

    /** Empty fixture: the tool must answer NO_DATA, never guess. */
    public static JsonFlakyHistorySource empty() {
        return new JsonFlakyHistorySource(new JSONObject());
    }

    @Override
    public Optional<FlakyRunHistory> load(String testName) {
        JSONArray runs = history.optJSONArray(testName);
        if (runs == null) {
            return Optional.empty();
        }
        List<FlakyRun> parsed = new ArrayList<>();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.getJSONObject(i);
            parsed.add(new FlakyRun(
                    run.optString("build", ""),
                    run.optBoolean("passed", false),
                    run.optBoolean("rerunPassed", false),
                    run.optString("relatedCommit", "")));
        }
        return Optional.of(new FlakyRunHistory(testName, parsed));
    }

    public static JsonFlakyHistorySource loadOrEmpty(Path historyFile) {
        try {
            return new JsonFlakyHistorySource(historyFile);
        } catch (IOException | UncheckedIOException | org.json.JSONException e) {
            return empty();
        }
    }
}
