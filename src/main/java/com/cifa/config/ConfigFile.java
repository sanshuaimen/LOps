package com.cifa.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigFile {

    // ponytail: the config format only needs flat scalar key/value pairs (LLM_PROVIDER,
    // OLLAMA_MODEL, CONTEXT_CHAR_BUDGET, DEFAULT_FORMAT), so one regex covers both
    // "key: value" (valid YAML for a flat map) and "key=value" (.env) without a YAML
    // dependency. Upgrade to a real YAML library if nested config is ever needed.
    private static final Pattern LINE = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*[:=]\\s*(.*?)\\s*$");

    private ConfigFile() {
    }

    public static Map<String, String> load(Path workingDir) {
        for (String name : List.of("config.yaml", "config.yml", ".env")) {
            Path candidate = workingDir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return parse(candidate);
            }
        }
        return Map.of();
    }

    static Map<String, String> parse(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                Matcher matcher = LINE.matcher(trimmed);
                if (matcher.matches()) {
                    String value = stripQuotes(matcher.group(2));
                    values.put(matcher.group(1), value);
                }
            }
        } catch (IOException e) {
            return Map.of();
        }
        return values;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
