package dev.codespire.orchestrator.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.context.ContextCredential;
import dev.codespire.encryption.EncryptionService;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    private WorkerContextCredentials packer(List<ContextProviderConfig> enabled) {
        WorkerContextCredentials wc = new WorkerContextCredentials();
        wc.encryption = encryption;
        wc.mapper = new ObjectMapper();
        wc.registry = new ContextProviderRegistry() {
            @Override
            public List<ContextProviderConfig> resolveAllEnabled() {
                return enabled;
            }
        };
        return wc;
    }

    private static ContextProviderConfig jira() {
        return new ContextProviderConfig(UUID.randomUUID(), "Acme Jira", "jira",
                "https://acme.atlassian.net", "basic", "bot@acme.com", "jira-api-token", "ACME", true, false);
    }

    private static ContextProviderConfig confluence() {
        return new ContextProviderConfig(UUID.randomUUID(), "Acme Confluence", "confluence",
                "https://acme.atlassian.net/wiki", "bearer", null, "conf-pat", "ENG", true, false);
    }

    @Test
    void packsEveryEnabledCredentialTheWorkerCanDecrypt() throws Exception {
        String cipher = packer(List.of(jira(), confluence())).packAll("acme").orElseThrow();
        assertFalse(cipher.contains("jira-api-token"), "the secret must not appear in cleartext");
        assertFalse(cipher.contains("conf-pat"), "the secret must not appear in cleartext");

        String json = encryption.decryptString(cipher, ContextCredential.aad("acme"));
        List<ContextCredential> back = new ObjectMapper().readValue(json, new TypeReference<>() {
        });
        assertEquals(2, back.size(), "both enabled providers are packed");
        assertEquals("jira", back.get(0).type());
        assertEquals("jira-api-token", back.get(0).secret());
        assertEquals("ACME", back.get(0).projectKeys());
        assertEquals("confluence", back.get(1).type());
        assertEquals("conf-pat", back.get(1).secret());
        assertEquals("ENG", back.get(1).projectKeys());
    }

    @Test
    void aCredentialForOneWorkspaceCannotBeReplayedAgainstAnother() {
        String cipher = packer(List.of(jira())).packAll("acme").orElseThrow();
        assertThrows(IllegalStateException.class,
                () -> encryption.decryptString(cipher, ContextCredential.aad("other-ws")));
    }

    @Test
    void emptyWhenNoContextProviderConfigured() {
        assertTrue(packer(List.of()).packAll("acme").isEmpty(),
                "no enabled context provider → no credential → worker assembles an empty context");
    }
}
