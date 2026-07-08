package dev.codespire.orchestrator.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.context.ContextCredential;
import dev.codespire.encryption.EncryptionService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context analog of {@link dev.codespire.orchestrator.provider.WorkerCredentialsTest}:
 * what the orchestrator packs onto GatherContext is never plaintext, is decryptable
 * by a worker holding the same keyset under the shared workspace-bound AAD (and only
 * that workspace's), and is absent when no context provider is configured.
 */
class WorkerContextCredentialsTest {

    private final EncryptionService encryption = new EncryptionService(EncryptionService.generateKeysetBase64());

    private WorkerContextCredentials packer(ContextProviderConfig resolved) {
        WorkerContextCredentials wc = new WorkerContextCredentials();
        wc.encryption = encryption;
        wc.mapper = new ObjectMapper();
        wc.registry = new ContextProviderRegistry() {
            @Override
            public Optional<ContextProviderConfig> resolveDefault() {
                return Optional.ofNullable(resolved);
            }
        };
        return wc;
    }

    private static ContextProviderConfig jira() {
        return new ContextProviderConfig(UUID.randomUUID(), "Acme Jira", "jira",
                "https://acme.atlassian.net", "basic", "bot@acme.com", "jira-api-token", "ACME", true, true);
    }

    @Test
    void packsAnEncryptedCredentialTheWorkerCanDecrypt() throws Exception {
        String cipher = packer(jira()).packDefault("acme").orElseThrow();
        assertFalse(cipher.contains("jira-api-token"), "the secret must not appear in cleartext");

        String json = encryption.decryptString(cipher, ContextCredential.aad("acme"));
        ContextCredential back = new ObjectMapper().readValue(json, ContextCredential.class);
        assertEquals("jira", back.type());
        assertEquals("https://acme.atlassian.net", back.baseUrl());
        assertEquals("basic", back.authKind());
        assertEquals("bot@acme.com", back.username());
        assertEquals("jira-api-token", back.secret());
        assertEquals("ACME", back.projectKeys());
    }

    @Test
    void aCredentialForOneWorkspaceCannotBeReplayedAgainstAnother() {
        String cipher = packer(jira()).packDefault("acme").orElseThrow();
        assertThrows(IllegalStateException.class,
                () -> encryption.decryptString(cipher, ContextCredential.aad("other-ws")));
    }

    @Test
    void emptyWhenNoContextProviderConfigured() {
        assertTrue(packer(null).packDefault("acme").isEmpty(),
                "no default context provider → no credential → worker assembles an empty context");
    }
}
