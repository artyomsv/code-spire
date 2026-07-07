package dev.codespire.orchestrator.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.scm.ScmCredential;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Packs a registered provider's decrypted credential into the opaque,
 * KEK-encrypted {@code ActionCommand.scmCredential} the worker consumes
 * (ADR-015). The orchestrator is the sole owner of the provider registry; the
 * worker never reads it — it receives just what a single command needs, bound
 * by AAD to the PR's workspace and readable only by a KEK holder.
 */
@ApplicationScoped
public class WorkerCredentials {

    @Inject
    ProviderRegistry providers;

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    /** Encrypt an already-resolved provider's credential (IntegrationSaga path). */
    public String pack(ScmProvider provider) {
        ScmCredential cred = new ScmCredential(provider.type(), provider.baseUrl(), provider.authKind(),
                provider.authUsername(), provider.secret());
        try {
            return encryption.encryptString(mapper.writeValueAsString(cred), ScmCredential.aad(provider.workspace()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to pack worker credential for " + provider.workspace(), e);
        }
    }

    /**
     * Resolve the enabled provider for a workspace and pack its credential
     * (ResultSaga path). Empty if the provider was disabled/removed mid-review —
     * the caller then skips the command rather than emit an uncredentialed one.
     */
    public Optional<String> packForWorkspace(String workspace) {
        return providers.resolveByWorkspace(workspace).map(this::pack);
    }
}
