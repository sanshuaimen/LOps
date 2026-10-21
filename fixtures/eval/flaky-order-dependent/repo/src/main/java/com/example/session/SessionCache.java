package com.example.session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Suite-shared session cache. Tests that assume an empty cache are exercising
 * shared state: the TTL sweep walks every entry and breaches its budget once
 * earlier suites pre-populate it.
 */
public class SessionCache {

    private final Map<String, Long> entries = new LinkedHashMap<>();
    private final long ttlMillis;

    public SessionCache(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    public void put(String sessionId) {
        entries.put(sessionId, System.currentTimeMillis());
    }

    public int size() {
        return entries.size();
    }

    public int evictStale(long deadlineMillis) throws InterruptedException {
        long start = System.currentTimeMillis();
        for (String sessionId : entries.keySet()) {
            if (System.currentTimeMillis() - start > deadlineMillis) {
                throw new IllegalStateException("TTL sweep incomplete (timed out after " + deadlineMillis + " ms)");
            }
            Thread.sleep(ttlMillis / 10);
            entries.remove(sessionId);
        }
        return entries.size();
    }
}
