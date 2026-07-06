package dev.codespire.orchestrator.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.scm.ScmCredential;
import dev.codespire.encryption.EncryptionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The ADR-015 cross-service contract: what the orchestrator packs is (a) never
 * plaintext, and (b) decryptable by a worker holding the same keyset using the
 * shared workspace-bound AAD — and ONLY that workspace's AAD.
 */
class WorkerCredentialsTest {

    private final EncryptionService encryption = new EncryptionService(EncryptionService.generateKeysetBase64());

    private WorkerCredentials packer() {
        WorkerCredentials wc = new WorkerCredentials();
        wc.encryption = encryption;
        wc.mapper = new ObjectMapper();
        return wc;
    }

    private static ScmProvider provider() {
        return new ScmProvider(UUID.randomUUID(), "CF", "bitbucket-cloud",
                "https://api.bitbucket.org/2.0", "acme", "bearer", null,
                "sk-secret-token", "acct-1", true, List.of());
    }

    @Test
    void packsAnEncryptedCredentialTheWorkerCanDecrypt() throws Exception {
        String cipher = packer().pack(provider());
        assertFalse(cipher.contains("sk-secret-token"), "the secret must not appear in cleartext");

        // worker side: same keyset, workspace-bound AAD
        String json = encryption.decryptString(cipher, ScmCredential.aad("acme"));
        ScmCredential back = new ObjectMapper().readValue(json, ScmCredential.class);
        assertEquals("https://api.bitbucket.org/2.0", back.baseUrl());
        assertEquals("bearer", back.authKind());
        assertEquals("sk-secret-token", back.secret());
        assertEquals("acct-1", back.botAccountId());
    }

    @Test
    void aCredentialForOneWorkspaceCannotBeReplayedAgainstAnother() {
        String cipher = packer().pack(provider());
        assertThrows(IllegalStateException.class, () -> encryption.decryptString(cipher, ScmCredential.aad("other-ws")));
    }
}
