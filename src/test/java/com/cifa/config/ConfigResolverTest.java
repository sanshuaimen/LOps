package com.cifa.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigResolverTest {

    @Test
    void cliValueWinsOverEverything() {
        assertEquals("cli", ConfigResolver.resolve("cli", "env", Map.of("KEY", "file"), "KEY", "default"));
    }

    @Test
    void envValueWinsOverFileAndDefaultWhenCliAbsent() {
        assertEquals("env", ConfigResolver.resolve(null, "env", Map.of("KEY", "file"), "KEY", "default"));
    }

    @Test
    void fileValueWinsOverDefaultWhenCliAndEnvAbsent() {
        assertEquals("file", ConfigResolver.resolve(null, null, Map.of("KEY", "file"), "KEY", "default"));
    }

    @Test
    void builtInDefaultUsedWhenNothingElsePresent() {
        assertEquals("default", ConfigResolver.resolve(null, null, Map.of(), "KEY", "default"));
    }

    @Test
    void blankValuesAreTreatedAsAbsentAtEveryTier() {
        assertEquals("default", ConfigResolver.resolve("  ", "", Map.of("KEY", " "), "KEY", "default"));
    }
}
