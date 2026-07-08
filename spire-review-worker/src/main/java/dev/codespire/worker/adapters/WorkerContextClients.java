package dev.codespire.worker.adapters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import dev.codespire.contract.context.ContextCredential;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.context.jira.JiraConfig;
import dev.codespire.context.jira.JiraContextProvider;
import dev.codespire.context.jira.JiraTicketKeys;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Per-command context-provider factory — the context analog of
 * {@link WorkerScmClients}. The credential rides each {@code GatherContext} as
 * opaque, KEK-encrypted ciphertext ({@code ActionCommand.contextCredential}) that
 * the orchestrator packed from the context-provider registry; the worker decrypts
 * it and builds a per-command provider, so one worker serves many workspaces.
 *
 * <p>Unlike SCM, context is OPTIONAL: no credential means no external source
 * configured, so this returns an empty provider list and the aggregator assembles
 * an empty context (the review still runs, just without ticket context).
 * Credential-less providers (repo rules, RAG, memory) can later be {@code @All}
 * CDI-injected here and merged with the per-command ones.
 */
@ApplicationScoped
public class WorkerContextClients {

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    public List<ContextProvider> forCommand(GatherContext command) {
        ContextCredential cred = unpack(command);
        if (cred == null) {
            return List.of();
        }
        return switch (cred.type()) {
            case "jira" -> List.of(new JiraContextProvider(jiraConfig(cred), mapper));
            default -> throw new IllegalStateException("Unsupported context provider type: " + cred.type());
        };
    }

    private ContextCredential unpack(GatherContext command) {
        String cipher = command.contextCredential();
        if (cipher == null || cipher.isBlank()) {
            return null;
        }
        String workspace = ReviewIds.parse(command.reviewId()).repo().workspace();
        try {
            return mapper.readValue(encryption.decryptString(cipher, ContextCredential.aad(workspace)),
                    ContextCredential.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to unpack context credential for " + workspace, e);
        }
    }

    private static JiraConfig jiraConfig(ContextCredential cred) {
        return new JiraConfig(cred.baseUrl(), cred.authKind(), cred.username(), cred.secret(),
                JiraTicketKeys.parseProjectKeys(cred.projectKeys()));
    }
}
