package dev.codespire.orchestrator.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.context.ContextCredential;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Resolves the global-default context provider and packs its config, encrypted,
 * onto a GatherContext command — the context analog of
 * {@link dev.codespire.orchestrator.llm.WorkerLlmCredentials}. Empty when no
 * context provider is configured, in which case GatherContext carries no
 * credential and the worker assembles an empty context.
 */
@ApplicationScoped
public class WorkerContextCredentials {

    @Inject
    ContextProviderRegistry registry;

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    /** The encrypted default context credential bound to a workspace, or empty when none is set. */
    public Optional<String> packDefault(String workspace) {
        return registry.resolveDefault().map(cfg -> pack(cfg, workspace));
    }

    private String pack(ContextProviderConfig cfg, String workspace) {
        ContextCredential cred = new ContextCredential(cfg.type(), cfg.baseUrl(),
                cfg.authKind(), cfg.username(), cfg.secret(), cfg.projectKeys());
        try {
            return encryption.encryptString(mapper.writeValueAsString(cred), ContextCredential.aad(workspace));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to pack context credential for " + workspace, e);
        }
    }
}
