package com.cifa.integration;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GitHubCommentPoster {

    private final String token;
    private final HttpClient client;

    public GitHubCommentPoster(String token, HttpClient client) {
        this.token = token;
        this.client = client;
    }

    public void postComment(String repoSlug, int prNumber, String body) throws IOException, InterruptedException {
        HttpRequest request = buildRequest(token, repoSlug, prNumber, body);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (isErrorStatus(response.statusCode())) {
            throw new IOException("GitHub API returned HTTP " + response.statusCode());
        }
    }

    static boolean isErrorStatus(int statusCode) {
        return statusCode >= 300;
    }

    static HttpRequest buildRequest(String token, String repoSlug, int prNumber, String body) {
        String uri = "https://api.github.com/repos/" + repoSlug + "/issues/" + prNumber + "/comments";
        return HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new JSONObject().put("body", body).toString()))
                .build();
    }

    public static Integer prNumberFromEventPath(String eventPath) {
        if (eventPath == null || eventPath.isBlank()) {
            return null;
        }
        try {
            String content = Files.readString(Path.of(eventPath));
            JSONObject json = new JSONObject(content);
            if (json.has("pull_request")) {
                return json.getJSONObject("pull_request").getInt("number");
            }
            if (json.has("number")) {
                return json.getInt("number");
            }
            return null;
        } catch (IOException | JSONException e) {
            return null;
        }
    }
}
