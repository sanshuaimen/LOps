package com.cifa.cli;

import com.cifa.llm.AnthropicProvider;
import com.cifa.llm.DeepSeekProvider;
import com.cifa.llm.FakeLLMProvider;
import com.cifa.llm.LLMProvider;
import com.cifa.llm.OllamaProvider;

import java.nio.file.Path;

/**
 * Provider factory for the auxiliary LLM commands (currently memory distill).
 * Same resolution order as Main (CLI flag > environment variable > built-in
 * default; secrets from the environment only), extracted so the second caller
 * does not become a second copy of Main's switch. The config file is
 * deliberately out of scope here: a maintenance command's one-shot LLM choice
 * stays on the command line.
 */
final class LlmProviders {

    record Selection(LLMProvider provider, String model) {
    }

    private LlmProviders() {
    }

    static Selection create(String providerName, String modelFlag, String fakeScript) throws Exception {
        return switch (providerName) {
            case "fake" -> {
                if (fakeScript != null && !fakeScript.isBlank()) {
                    yield new Selection(FakeLLMProvider.recorded(Path.of(fakeScript)), "fake");
                }
                yield new Selection(new FakeLLMProvider(), "fake");
            }
            case "ollama" -> {
                String model = model(modelFlag, "OLLAMA_MODEL", "deepseek-r1:8b");
                String url = envOrDefault("OLLAMA_URL", "http://localhost:11434");
                yield new Selection(new OllamaProvider(url, model), model);
            }
            case "anthropic" -> {
                String apiKey = requiredEnv("ANTHROPIC_API_KEY", providerName);
                String model = model(modelFlag, "ANTHROPIC_MODEL", "claude-sonnet-5");
                yield new Selection(new AnthropicProvider(apiKey, model), model);
            }
            case "deepseek" -> {
                String apiKey = requiredEnv("DEEPSEEK_API_KEY", providerName);
                String model = model(modelFlag, "DEEPSEEK_MODEL", DeepSeekProvider.DEFAULT_MODEL);
                yield new Selection(new DeepSeekProvider(apiKey, model), model);
            }
            default -> throw new MemoryCommand.Usage("unsupported --provider '" + providerName
                    + "' (fake|ollama|anthropic|deepseek)", true);
        };
    }

    private static String model(String modelFlag, String envName, String defaultValue) {
        if (modelFlag != null && !modelFlag.isBlank()) {
            return modelFlag;
        }
        return envOrDefault(envName, defaultValue);
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String requiredEnv(String name, String providerName) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new MemoryCommand.Usage(name + " environment variable is required for --provider " + providerName, true);
        }
        return value;
    }
}
