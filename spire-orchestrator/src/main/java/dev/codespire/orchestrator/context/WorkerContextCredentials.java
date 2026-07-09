package dev.codespire.orchestrator.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.context.ContextCredential;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Resolves EVERY enabled context provider and packs their configs, encrypted, onto
 * a GatherContext command so the worker can match a PR's references against all of
 * them (Jira and Confluence at once — no single "default"). The context analog of
 * {@link dev.codespire.orchestrator.llm.WorkerLlmCredentials}, but list-valued.
 * Empty when no context provider is configured, in which case GatherContext carries
 * no credential and the worker assembles an empty context.
 */
@ApplicationScoped
public class WorkerContextCredentials {

    @Inject
    ContextProviderRegistry registry;

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    /** The encrypted list of all enabled context credentials bound to a workspace, or empty when none exist. */
    public Optional<String> packAll(String workspace) {
        List<ContextCredential> creds = registry.resolveAllEnabled().stream()
                .map(cfg -> new ContextCredential(cfg.type(), cfg.baseUrl(),
                        cfg.authKind(), cfg.username(), cfg.secret(), cfg.projectKeys()))
                .toList();
        if (creds.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    encryption.encryptString(mapper.writeValueAsString(creds), ContextCredential.aad(workspace)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to pack context credentials for " + workspace, e);
        }
    }
}
