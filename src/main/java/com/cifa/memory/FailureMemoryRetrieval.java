package com.cifa.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lexical retrieval over the failure memory: hand-rolled Okapi BM25
 * (k1=1.5, b=0.75) over keyword tokens. No embeddings, no vector store —
 * CI logs are vocabulary-dense, exact error signatures dominate similarity,
 * and the retrieval quality protocol (P3 task 6) measures whether this is
 * actually good enough instead of assuming.
 */
public final class FailureMemoryRetrieval {

    static final double K1 = 1.5;
    static final double B = 0.75;

    private final List<Scored> corpus;

    /** Precomputed BM25 statistics over the store's entries. */
    public FailureMemoryRetrieval(List<FailureMemoryEntry> entries) {
        List<List<String>> tokenized = entries.stream()
                .map(FailureMemoryEntry::retrievalTokens)
                .toList();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (List<String> tokens : tokenized) {
            new java.util.HashSet<>(tokens).forEach(token ->
                    documentFrequency.merge(token, 1, Integer::sum));
        }
        double totalLength = tokenized.stream().mapToInt(List::size).sum();
        double avgLength = tokenized.isEmpty() ? 0 : totalLength / tokenized.size();

        List<Scored> built = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            built.add(new Scored(entries.get(i), tokenized.get(i), documentFrequency, avgLength, entries.size()));
        }
        this.corpus = built;
    }

    /**
     * Top-k entries above {@code minScore}, never returning an entry whose
     * caseId equals {@code excludeCaseId} — the mechanical half of the leakage
     * protocol (a case must not retrieve its own notebook page).
     */
    public List<FailureMemoryEntry> retrieve(String query, String excludeCaseId, int k, double minScore) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty() || k <= 0) {
            return List.of();
        }
        return corpus.stream()
                .filter(doc -> !doc.entry.caseId().equals(excludeCaseId))
                .map(doc -> Map.entry(doc.entry, doc.score(queryTokens)))
                .filter(match -> match.getValue() >= minScore)
                .sorted(Map.Entry.<FailureMemoryEntry, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(k)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Lowercase word tokens, length >= 3, camelCase and dot-path split — error
     * signatures live in identifiers (NullPointerException, targetModule) and
     * paths (com.example.Foo), not in prose. */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        for (String raw : text.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9_]+")) {
            if (raw.length() < 3) {
                continue;
            }
            tokens.add(raw);
        }
        return tokens;
    }

    private static final class Scored {
        private final FailureMemoryEntry entry;
        private final List<String> tokens;
        private final Map<String, Integer> termFrequency;
        private final Map<String, Integer> documentFrequency;
        private final double avgLength;
        private final int documentCount;

        private Scored(FailureMemoryEntry entry, List<String> tokens, Map<String, Integer> documentFrequency,
                       double avgLength, int documentCount) {
            this.entry = entry;
            this.tokens = tokens;
            this.documentFrequency = documentFrequency;
            this.avgLength = avgLength;
            this.documentCount = documentCount;
            this.termFrequency = new HashMap<>();
            tokens.forEach(token -> termFrequency.merge(token, 1, Integer::sum));
        }

        private double score(List<String> queryTokens) {
            double score = 0.0;
            for (String token : queryTokens) {
                Integer tf = termFrequency.get(token);
                if (tf == null) {
                    continue;
                }
                Integer df = documentFrequency.get(token);
                double idf = Math.log(1 + (documentCount - df + 0.5) / (df + 0.5));
                double tfNorm = tf * (K1 + 1)
                        / (tf + K1 * (1 - B + B * tokens.size() / avgLength));
                score += idf * tfNorm;
            }
            return score;
        }
    }
}
