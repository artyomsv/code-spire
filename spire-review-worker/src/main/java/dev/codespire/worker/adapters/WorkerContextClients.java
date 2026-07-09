package dev.codespire.worker.adapters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import dev.codespire.contract.context.ContextCredential;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.context.confluence.ConfluenceConfig;
import dev.codespire.context.confluence.ConfluenceContextProvider;
import dev.codespire.context.confluence.ConfluenceLinks;
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
 * an empty context (the review still runs, just without ticket context). The
 * command carries EVERY enabled provider's credential, so the aggregator can match
 * a PR's references against all of them. Credential-less providers (repo rules,
 * RAG, memory) can later be {@code @All} CDI-injected here and merged in.
 */
@ApplicationScoped
public class WorkerContextClients {

    private static final com.fasterxml.jackson.core.type.TypeReference<List<ContextCredential>> CRED_LIST =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    public List<ContextProvider> forCommand(GatherContext command) {
        List<ContextProvider> providers = new java.util.ArrayList<>();
        for (ContextCredential cred : unpack(command)) {
            switch (cred.type()) {
                case "jira" -> providers.add(new JiraContextProvider(jiraConfig(cred), mapper));
                case "confluence" -> providers.add(new ConfluenceContextProvider(confluenceConfig(cred), mapper));
                default -> throw new IllegalStateException("Unsupported context provider type: " + cred.type());
            }
        }
        return providers;
    }

    private List<ContextCredential> unpack(GatherContext command) {
        String cipher = command.contextCredential();
        if (cipher == null || cipher.isBlank()) {
            return List.of();
        }
        String workspace = ReviewIds.parse(command.reviewId()).repo().workspace();
        try {
            return mapper.readValue(encryption.decryptString(cipher, ContextCredential.aad(workspace)), CRED_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to unpack context credentials for " + workspace, e);
        }
    }

    private static JiraConfig jiraConfig(ContextCredential cred) {
        return new JiraConfig(cred.baseUrl(), cred.authKind(), cred.username(), cred.secret(),
                JiraTicketKeys.parseProjectKeys(cred.projectKeys()));
    }

    private static ConfluenceConfig confluenceConfig(ContextCredential cred) {
        // projectKeys carries the optional space-key allow-list for Confluence (same generic registry column).
        return new ConfluenceConfig(cred.baseUrl(), cred.authKind(), cred.username(), cred.secret(),
                ConfluenceLinks.parseSpaceKeys(cred.projectKeys()));
    }
}
