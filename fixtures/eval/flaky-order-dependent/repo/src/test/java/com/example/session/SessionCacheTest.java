package com.example.session;

public class SessionCacheTest {

    public int evictsAfterTtl() throws Exception {
        SessionCache cache = new SessionCache(100);
        cache.put("s-1");
        cache.put("s-2");
        cache.put("s-3");
        if (cache.evictStale(2000) != 0) {
            throw new AssertionError("stale entries survived the sweep");
        }
        return cache.size();
    }
}
