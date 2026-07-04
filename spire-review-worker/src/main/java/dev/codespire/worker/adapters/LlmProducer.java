package dev.codespire.worker.adapters;

import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.llm.LangChain4jLlmProvider;
import dev.codespire.llm.LlmConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * LLM provider selection: {@code spire.llm.provider} = stub | openai-compatible.
 * NO default provider or model — the operator brings their own (ADR-001).
 */
@ApplicationScoped
public class LlmProducer {

    @ConfigProperty(name = "spire.llm.provider")
    String provider;

    @ConfigProperty(name = "spire.llm.base-url")
    Optional<String> baseUrl;

    @ConfigProperty(name = "spire.llm.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "spire.llm.model")
    Optional<String> model;

    @ConfigProperty(name = "spire.llm.temperature", defaultValue = "0.2")
    double temperature;

    @Produces
    @Singleton
    LlmProvider llmProvider() {
        return switch (provider) {
            case "openai-compatible" -> LangChain4jLlmProvider.openAiCompatible(new LlmConfig(
                    required(baseUrl, "spire.llm.base-url"),
                    required(apiKey, "spire.llm.api-key"),
                    required(model, "spire.llm.model"),
                    temperature));
            case "stub" -> new StubLlmProvider();
            default -> throw new IllegalStateException("Unknown spire.llm.provider '" + provider
                    + "' — expected stub | openai-compatible");
        };
    }

    private static String required(Optional<String> value, String key) {
        return value.filter(v -> !v.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "Config '" + key + "' is required for spire.llm.provider=openai-compatible"));
    }

    /** Canned review citing the stub diff — dev/test only, self-labeled STUB output. */
    static final class StubLlmProvider implements LlmProvider {

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public CompletionStage<Completion> complete(Prompt prompt, ModelParams params) {
            String canned = """
                    { "summary": "STUB summary: pipeline works end-to-end (not a real review).",
                      "findings": [
                        { "path": "src/Demo.java", "line": 2, "endLine": 2, "severity": "INFO",
                          "message": "STUB finding: phase 1 scaffolding output.", "suggestion": null }
                      ] }
                    """;
            return CompletableFuture.completedFuture(
                    new Completion(canned, new ModelUsage("stub-model", 0, 0, 0)));
        }
    }
}
