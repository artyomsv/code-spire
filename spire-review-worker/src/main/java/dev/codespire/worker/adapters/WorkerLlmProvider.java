package dev.codespire.worker.adapters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand.AnswerFollowUp;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.LlmCredential;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.llm.LangChain4jLlmProvider;
import dev.codespire.llm.LlmConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Builds the LLM client for a GenerateReview command from the brokered, encrypted
 * {@link LlmCredential} (ADR-018) — the LLM analog of {@link WorkerScmClients}.
 * {@code spire.llm.provider=stub} forces the canned stub (dev/test); otherwise the
 * credential's type selects the model. No LLM config comes from env any more.
 */
@ApplicationScoped
public class WorkerLlmProvider {

    /** The provider + the per-call params (temperature/maxTokens) resolved from the credential. */
    public record LlmClient(LlmProvider provider, ModelParams params) {
    }

    @ConfigProperty(name = "spire.llm.provider", defaultValue = "registry")
    String mode; // "stub" forces the stub; any other value uses the brokered credential

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    private final LlmClient stub = new LlmClient(new StubLlmProvider(),
            new ModelParams("stub-model", 0.2, null));

    public LlmClient forCommand(GenerateReview command) {
        if ("stub".equals(mode)) {
            return stub;
        }
        LlmCredential cred = unpack(command.reviewId(), command.llmCredential());
        if (cred == null) {
            return stub; // no credential brokered — safe fallback (shouldn't happen in active mode)
        }
        return clientFor(cred);
    }

    public LlmClient forCommand(AnswerFollowUp command) {
        if ("stub".equals(mode)) {
            return stub;
        }
        LlmCredential cred = unpack(command.reviewId(), command.llmCredential());
        if (cred == null) {
            return stub;
        }
        return clientFor(cred);
    }

    /** Build the model from a decrypted credential (no network call — model init is lazy). */
    static LlmClient clientFor(LlmCredential cred) {
        LlmConfig config = new LlmConfig(cred.baseUrl(), cred.apiKey(), cred.model(), cred.temperature());
        LlmProvider provider = switch (cred.type()) {
            case "openai" -> LangChain4jLlmProvider.openAiCompatible(config);
            case "anthropic" -> LangChain4jLlmProvider.anthropic(config);
            case "gemini" -> LangChain4jLlmProvider.gemini(config);
            default -> throw new IllegalStateException("Unsupported LLM provider type: " + cred.type());
        };
        return new LlmClient(provider,
                new ModelParams(cred.model(), cred.temperature(), cred.maxTokens(), cred.profile()));
    }

    private LlmCredential unpack(String reviewId, String llmCredential) {
        if (llmCredential == null || llmCredential.isBlank()) {
            return null;
        }
        String workspace = ReviewIds.parse(reviewId).repo().workspace();
        try {
            return mapper.readValue(
                    encryption.decryptString(llmCredential, LlmCredential.aad(workspace)), LlmCredential.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to unpack LLM credential for " + workspace, e);
        }
    }

    /** Canned response for dev/test — never a real review (obvious STUB markers). */
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
