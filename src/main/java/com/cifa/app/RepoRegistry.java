package com.cifa.app;

import com.cifa.security.RepositoryPathPolicy;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side repository whitelist (P1 plan, explicit assumption): request
 * bodies carry an alias, never a path. Aliases are configured (or registered
 * programmatically in tests) and resolve through {@link RepositoryPathPolicy}
 * so the P0 path boundary still applies to whatever the alias points at.
 */
@Component
public class RepoRegistry {

    private final Map<String, Path> repos = new ConcurrentHashMap<>();

    public RepoRegistry(@Value("${diagnosis.repos-json:}") String reposJson) {
        if (reposJson != null && !reposJson.isBlank()) {
            JSONObject json = new JSONObject(reposJson);
            for (String alias : json.keySet()) {
                repos.put(alias, Path.of(json.getString(alias)));
            }
        }
    }

    public void register(String alias, Path repoRoot) {
        // validates immediately: unknown/escaping roots fail at registration, not at run time
        repos.put(alias, new RepositoryPathPolicy(repoRoot).root());
    }

    public Path resolve(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("repo alias is required");
        }
        Path root = repos.get(alias);
        if (root == null) {
            throw new IllegalArgumentException("unknown repo alias: " + alias);
        }
        return root;
    }

    public boolean knows(String alias) {
        return alias != null && repos.containsKey(alias);
    }
}
