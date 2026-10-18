package com.cifa.evidence;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Task-scoped registry of evidence. Only deterministic tool code may register
 * entries; ids are assigned here, so the model can reference evidence but can
 * never mint new ids. Each task gets its own catalog instance, which is what
 * keeps evidence from leaking between tasks.
 */
public final class EvidenceCatalog {

    private final String taskId;
    private final int maxEntries;
    private final Map<String, Evidence> byId = new LinkedHashMap<>();
    private final Map<EvidenceType, Integer> counters = new EnumMap<>(EvidenceType.class);

    public EvidenceCatalog(String taskId) {
        this(taskId, 500);
    }

    public EvidenceCatalog(String taskId, int maxEntries) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("task id is required");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("evidence catalog capacity must be positive");
        }
        this.taskId = taskId;
        this.maxEntries = maxEntries;
    }

    public synchronized Evidence register(EvidenceType type, String locator, String content, String toolName) {
        return register(type, locator, content, toolName, Evidence.NO_LINE, Evidence.NO_LINE, "");
    }

    public synchronized Evidence register(EvidenceType type, String locator, String content, String toolName,
                                          int startLine, int endLine, String commit) {
        if (type == null) {
            throw new IllegalArgumentException("evidence type is required");
        }
        if (locator == null || locator.isBlank()) {
            throw new IllegalArgumentException("evidence locator is required");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("evidence content is required");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("evidence must be registered by a named tool");
        }
        if (byId.size() >= maxEntries) {
            throw new IllegalStateException("evidence catalog is full: " + maxEntries);
        }
        int sequence = counters.merge(type, 1, Integer::sum);
        Evidence evidence = new Evidence(type.id(sequence), type, locator, content, Evidence.sha256(content),
                toolName, startLine, endLine, commit);
        byId.put(evidence.id(), evidence);
        return evidence;
    }

    /** Trims and upper-cases before lookup, so "log-001 " still resolves; unknown ids stay unknown. */
    public synchronized Optional<Evidence> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.trim().toUpperCase(Locale.ROOT)));
    }

    public synchronized Optional<Evidence> findByLocator(EvidenceType type, String locator) {
        if (type == null || locator == null) {
            return Optional.empty();
        }
        return byId.values().stream()
                .filter(evidence -> evidence.type() == type && evidence.locator().equals(locator))
                .findFirst();
    }

    public synchronized List<Evidence> byType(EvidenceType type) {
        List<Evidence> matches = new ArrayList<>();
        for (Evidence evidence : byId.values()) {
            if (evidence.type() == type) {
                matches.add(evidence);
            }
        }
        return List.copyOf(matches);
    }

    public synchronized List<Evidence> all() {
        return List.copyOf(byId.values());
    }

    public synchronized int size() {
        return byId.size();
    }

    public String taskId() {
        return taskId;
    }
}
