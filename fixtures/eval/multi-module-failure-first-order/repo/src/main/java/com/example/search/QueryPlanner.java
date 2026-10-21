package com.example.search;

import java.util.ArrayDeque;
import java.util.Deque;

public class QueryPlanner {

    private final Deque<String> pending = new ArrayDeque<>();
    private boolean cacheOpen = true;

    public void openCache() {
        cacheOpen = true;
        pending.clear();
    }

    public void closeCache() {
        cacheOpen = false;
    }

    public void enqueue(String term) {
        requireOpen();
        pending.add(term);
    }

    public java.util.List<String> plan(String query) {
        requireOpen();
        for (String term : query.split("\\s+")) {
            pending.add(term);
        }
        return new java.util.ArrayList<>(pending);
    }

    public java.util.List<String> planBoolean(String query, boolean fuzzy) {
        requireOpen();
        java.util.List<String> terms = new java.util.ArrayList<>();
        for (String term : query.split("\\s+")) {
            terms.add(fuzzy ? term + "~" : term);
        }
        drain();
        return terms;
    }

    private void drain() {
        if (!cacheOpen) {
            throw new IllegalStateException("planner cache closed before drain");
        }
        pending.clear();
    }

    private void requireOpen() {
        if (!cacheOpen) {
            throw new IllegalStateException("planner cache closed");
        }
    }
}
