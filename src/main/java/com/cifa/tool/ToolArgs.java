package com.cifa.tool;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Coercion helpers for model-supplied tool arguments. */
public final class ToolArgs {

    private ToolArgs() {
    }

    public static String requireString(Map<String, Object> args, String key) {
        String value = optionalString(args, key)
                .orElseThrow(() -> new IllegalArgumentException("missing required argument: " + key));
        if (value.isBlank()) {
            throw new IllegalArgumentException("argument must be non-empty: " + key);
        }
        return value;
    }

    public static Optional<String> optionalString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return Optional.empty();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    public static int requireInt(Map<String, Object> args, String key) {
        return optionalInt(args, key)
                .orElseThrow(() -> new IllegalArgumentException("missing required argument: " + key));
    }

    public static Optional<Integer> optionalInt(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Number number) {
            return Optional.of(number.intValue());
        }
        try {
            return Optional.of(Integer.parseInt(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("argument must be an integer: " + key);
        }
    }

    public static String requireOneOf(Map<String, Object> args, String key, List<String> allowed) {
        String value = optionalString(args, key)
                .map(text -> text.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("missing required argument: " + key));
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("argument " + key + " must be one of " + allowed);
        }
        return value;
    }
}
