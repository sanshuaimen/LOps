package com.cifa.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GitHubCommentPosterTest {

    @Test
    void buildRequestTargetsCorrectUrlAndUsesBearerAuth() {
        HttpRequest request = GitHubCommentPoster.buildRequest("test-token-123", "octocat/hello-world", 42, "report body");

        assertEquals("https://api.github.com/repos/octocat/hello-world/issues/42/comments", request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals("Bearer test-token-123", request.headers().firstValue("Authorization").orElse(null));
        assertEquals("application/vnd.github+json", request.headers().firstValue("Accept").orElse(null));
    }

    @Test
    void prNumberFromEventPathReadsPullRequestNumber(@TempDir Path tempDir) throws Exception {
        Path eventFile = tempDir.resolve("event.json");
        Files.writeString(eventFile, "{\"pull_request\": {\"number\": 17}}");

        assertEquals(17, GitHubCommentPoster.prNumberFromEventPath(eventFile.toString()));
    }

    @Test
    void prNumberFromEventPathReadsTopLevelNumberFallback(@TempDir Path tempDir) throws Exception {
        Path eventFile = tempDir.resolve("event.json");
        Files.writeString(eventFile, "{\"number\": 9}");

        assertEquals(9, GitHubCommentPoster.prNumberFromEventPath(eventFile.toString()));
    }

    @Test
    void prNumberFromEventPathReturnsNullWhenPathIsNullOrMissing() {
        assertNull(GitHubCommentPoster.prNumberFromEventPath(null));
        assertNull(GitHubCommentPoster.prNumberFromEventPath("/does/not/exist.json"));
    }

    @Test
    void isErrorStatusTreatsAnythingAtOrAbove300AsAnError() {
        assertFalse(GitHubCommentPoster.isErrorStatus(200));
        assertFalse(GitHubCommentPoster.isErrorStatus(201));
        assertFalse(GitHubCommentPoster.isErrorStatus(299));
        assertTrue(GitHubCommentPoster.isErrorStatus(300));
        assertTrue(GitHubCommentPoster.isErrorStatus(401));
        assertTrue(GitHubCommentPoster.isErrorStatus(404));
        assertTrue(GitHubCommentPoster.isErrorStatus(500));
    }
}
