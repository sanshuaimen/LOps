package com.cifa.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigFileTest {

    @Test
    void loadsFlatKeyValuePairsFromConfigYaml(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.yaml"), """
                LLM_PROVIDER: anthropic
                OLLAMA_MODEL: llama3
                # a comment line, ignored
                DEFAULT_FORMAT: html
                """);

        Map<String, String> config = ConfigFile.load(dir);

        assertEquals("anthropic", config.get("LLM_PROVIDER"));
        assertEquals("llama3", config.get("OLLAMA_MODEL"));
        assertEquals("html", config.get("DEFAULT_FORMAT"));
        assertEquals(3, config.size());
    }

    @Test
    void loadsEqualsStyleDotEnvWhenNoConfigYamlPresent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"), "LLM_PROVIDER=ollama\nCONTEXT_CHAR_BUDGET=3000\n");

        Map<String, String> config = ConfigFile.load(dir);

        assertEquals("ollama", config.get("LLM_PROVIDER"));
        assertEquals("3000", config.get("CONTEXT_CHAR_BUDGET"));
    }

    @Test
    void returnsEmptyMapWhenNoConfigFilePresent(@TempDir Path dir) {
        assertTrue(ConfigFile.load(dir).isEmpty());
    }

    @Test
    void configYamlTakesPrecedenceOverDotEnvWhenBothPresent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.yaml"), "LLM_PROVIDER: anthropic\n");
        Files.writeString(dir.resolve(".env"), "LLM_PROVIDER=ollama\n");

        assertEquals("anthropic", ConfigFile.load(dir).get("LLM_PROVIDER"));
    }
}
