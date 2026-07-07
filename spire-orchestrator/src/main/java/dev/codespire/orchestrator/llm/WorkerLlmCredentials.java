package dev.codespire.orchestrator.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.llm.LlmCredential;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Resolves the global-default LLM provider and packs its config, encrypted, onto a
 * GenerateReview command (ADR-018) — the LLM analog of
 * {@link dev.codespire.orchestrator.provider.WorkerCredentials} for SCM.
 */
@ApplicationScoped
public class WorkerLlmCredentials {

    @Inject
    LlmProviderRegistry registry;

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    /** The encrypted default LLM credential bound to a workspace, or empty when no default is set. */
    public Optional<String> packDefault(String workspace) {
        return registry.resolveDefault().map(cfg -> pack(cfg, workspace));
    }

    private String pack(LlmProviderConfig cfg, String workspace) {
        LlmCredential cred = new LlmCredential(cfg.type(), cfg.baseUrl(), cfg.apiKey(),
                cfg.model(), cfg.temperature(), cfg.maxTokens());
        try {
            return encryption.encryptString(mapper.writeValueAsString(cred), LlmCredential.aad(workspace));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to pack LLM credential for " + workspace, e);
        }
    }
}
