package com.cifa.config;

import com.cifa.llm.AnthropicProvider;
import com.cifa.llm.FakeLLMProvider;
import com.cifa.llm.LLMProvider;
import com.cifa.llm.OllamaProvider;
import com.cifa.service.AgentDiagnosisService;
import com.cifa.service.BaselineFailureAnalyzer;
import com.cifa.tool.FlakyHistorySource;
import com.cifa.tool.JsonFlakyHistorySource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Wires the framework-free P0 core services as beans. The core classes
 * themselves stay Spring-free (ArchitectureTest); this is the only place they
 * touch the container.
 */
@Configuration
public class DiagnosisBeansConfig {

    @Bean
    public AgentDiagnosisService agentDiagnosisService() {
        return new AgentDiagnosisService();
    }

    @Bean
    public BaselineFailureAnalyzer baselineFailureAnalyzer() {
        return new BaselineFailureAnalyzer();
    }

    /** Server-side flaky history file; empty property = NO_DATA source. Never request-supplied. */
    @Bean
    public FlakyHistorySource flakyHistorySource(
            @Value("${diagnosis.flaky-history:}") String historyPath) {
        return historyPath.isBlank()
                ? JsonFlakyHistorySource.empty()
                : JsonFlakyHistorySource.loadOrEmpty(Path.of(historyPath));
    }

    /**
     * Same provider semantics as the CLI: fake (optionally scripted), ollama,
     * anthropic. Real-model runs are opt-in via configuration.
     */
    @Bean
    public LLMProvider llmProvider(
            @Value("${diagnosis.llm.provider:fake}") String provider,
            @Value("${diagnosis.llm.fake-script:}") String fakeScript,
            @Value("${diagnosis.llm.ollama-url:http://localhost:11434}") String ollamaUrl,
            @Value("${diagnosis.llm.ollama-model:deepseek-r1:8b}") String ollamaModel,
            @Value("${diagnosis.llm.anthropic-api-key:}") String anthropicApiKey,
            @Value("${diagnosis.llm.anthropic-model:claude-sonnet-5}") String anthropicModel) {
        return switch (provider) {
            case "fake" -> {
                if (fakeScript.isBlank()) {
                    yield new FakeLLMProvider();
                }
                try {
                    yield FakeLLMProvider.recorded(Path.of(fakeScript));
                } catch (Exception e) {
                    throw new IllegalStateException("unreadable fake script: " + fakeScript, e);
                }
            }
            case "ollama" -> new OllamaProvider(ollamaUrl, ollamaModel);
            case "anthropic" -> {
                if (anthropicApiKey.isBlank()) {
                    throw new IllegalStateException("diagnosis.llm.anthropic-api-key is required for provider=anthropic");
                }
                yield new AnthropicProvider(anthropicApiKey, anthropicModel);
            }
            default -> throw new IllegalStateException(
                    "unsupported diagnosis.llm.provider '" + provider + "' (fake|ollama|anthropic)");
        };
    }
}
