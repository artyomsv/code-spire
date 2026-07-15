package dev.codespire.orchestrator.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ScmCredential;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
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
    ReviewProjection projection;

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
     * Resolve the enabled provider for a REVIEW and pack its credential (ResultSaga /
     * rerun / retry path). The provider is disambiguated by the SCM type persisted with
     * the review, so a workspace name registered on more than one SCM still brokers the
     * right provider — unlike resolving by workspace alone. Empty if the provider was
     * disabled/removed mid-review, so the caller skips rather than emit uncredentialed.
     */
    public Optional<String> packForReview(String reviewId) {
        return resolveForReview(reviewId).map(this::pack);
    }

    private Optional<ScmProvider> resolveForReview(String reviewId) {
        RepoRef repo = ReviewIds.parse(reviewId).repo();
        // Prefer the review's stored SCM type (registered header); fall back to
        // workspace-only for reviews that predate a stored type.
        String type = projection.providerTypeOf(reviewId).filter(t -> !t.isBlank()).orElse(null);
        return type == null
                ? providers.resolveByWorkspace(repo.workspace())
                : providers.resolve(type, repo.workspace());
    }
}
