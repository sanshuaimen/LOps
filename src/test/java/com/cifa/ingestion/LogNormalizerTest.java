package com.cifa.ingestion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LogNormalizerTest {

    @Test
    void stripsAnsiGroupMarkersBannersAndTimestamps() {
        String raw = "2026-07-20T10:15:32.123Z [36m::group::Run tests[0m\n"
                + "2026-07-20T10:15:33.000Z [ERROR] Build failed\n"
                + "[Pipeline] { (Test)\n"
                + "::endgroup::\n";
        String expected = "Run tests\n[ERROR] Build failed\n";
        assertEquals(expected, LogNormalizer.normalize(raw));
    }

    @Test
    void stripsGithubAnnotationPrefixes() {
        String raw = "::error::Process completed with exit code 1\n";
        assertEquals("Process completed with exit code 1\n", LogNormalizer.normalize(raw));
    }

    @Test
    void stripsMidLineAnsiColorCodes() {
        String raw = "Build [32mSUCCESS[0m: 12 tests passed\n";
        assertEquals("Build SUCCESS: 12 tests passed\n", LogNormalizer.normalize(raw));
    }
}
