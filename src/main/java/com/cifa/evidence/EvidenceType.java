package com.cifa.evidence;

import java.util.Locale;

public enum EvidenceType {
    LOG("LOG"),
    TEST_REPORT("TEST"),
    SOURCE("SRC"),
    GIT("GIT"),
    DEPENDENCY("DEP"),
    FLAKY_HISTORY("FLAKY");

    private final String prefix;

    EvidenceType(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    public static EvidenceType fromPrefix(String prefix) {
        for (EvidenceType type : values()) {
            if (type.prefix.equalsIgnoreCase(prefix)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown evidence type prefix: " + prefix);
    }

    public String id(int sequence) {
        return String.format(Locale.ROOT, "%s-%03d", prefix, sequence);
    }
}
