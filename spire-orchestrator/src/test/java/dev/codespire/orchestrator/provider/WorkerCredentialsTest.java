package dev.codespire.orchestrator.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ScmCredential;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    }

    @Test
    void aCredentialForOneWorkspaceCannotBeReplayedAgainstAnother() {
        String cipher = packer().pack(provider());
        assertThrows(IllegalStateException.class, () -> encryption.decryptString(cipher, ScmCredential.aad("other-ws")));
    }

    @Test
    void packForReview_resolvesByTheReviewsStoredScmType() {
        WorkerCredentials wc = packer();
        wc.projection = new ReviewProjection() {
            @Override
            public Optional<String> providerTypeOf(String reviewId) {
                return Optional.of("bitbucket-cloud");
            }
        };
        List<String> resolvedByType = new ArrayList<>();
        wc.providers = new ProviderRegistry() {
            @Override
            public Optional<ScmProvider> resolve(String type, String workspace) {
                resolvedByType.add(type + "@" + workspace);
                return Optional.of(provider());
            }

            @Override
            public Optional<ScmProvider> resolveByWorkspace(String workspace) {
                throw new AssertionError("must resolve by (type, workspace) when the review stores a type");
            }
        };

        Optional<String> cred = wc.packForReview(ReviewIds.reviewId(new RepoRef("acme", "web"), 5L));

        assertTrue(cred.isPresent(), "a resolvable provider yields a packed credential");
        assertEquals(List.of("bitbucket-cloud@acme"), resolvedByType,
                "the workspace-shared collision is disambiguated by the review's stored SCM type");
    }
}
