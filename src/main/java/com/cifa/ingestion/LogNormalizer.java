package com.cifa.ingestion;

import java.util.regex.Pattern;

public final class LogNormalizer {

    private static final Pattern ANSI = Pattern.compile("\\[[;\\d]*[A-Za-z]");
    private static final Pattern TIMESTAMP_PREFIX =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z?\\s+");
    private static final Pattern JENKINS_BANNER = Pattern.compile("^\\[Pipeline\\] .*$");
    private static final Pattern GROUP_START = Pattern.compile("^::group::(.*)$");
    private static final Pattern GROUP_END = Pattern.compile("^::endgroup::\\s*$");
    private static final Pattern GH_ANNOTATION = Pattern.compile("^::(?:error|warning)::(.*)$");

    private LogNormalizer() {
    }

    public static String normalize(String rawLog) {
        StringBuilder out = new StringBuilder();
        for (String rawLine : rawLog.split("\n", -1)) {
            String line = ANSI.matcher(rawLine).replaceAll("");
            line = TIMESTAMP_PREFIX.matcher(line).replaceFirst("");
            String trimmed = line.trim();

            if (GROUP_END.matcher(trimmed).matches() || JENKINS_BANNER.matcher(trimmed).matches()) {
                continue;
            }
            var groupMatch = GROUP_START.matcher(trimmed);
            if (groupMatch.matches()) {
                out.append(groupMatch.group(1)).append('\n');
                continue;
            }
            var annotationMatch = GH_ANNOTATION.matcher(trimmed);
            if (annotationMatch.matches()) {
                out.append(annotationMatch.group(1)).append('\n');
                continue;
            }
            out.append(line).append('\n');
        }
        if (out.length() > 0 && rawLog.endsWith("\n")) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }
}
