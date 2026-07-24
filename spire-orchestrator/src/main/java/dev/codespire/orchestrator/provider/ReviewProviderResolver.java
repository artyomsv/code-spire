package dev.codespire.orchestrator.provider;

import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Resolves the SCM provider for a review by the SCM type persisted with it (the registered header,
 * {@code review_status.provider_type}), falling back to workspace-only for reviews that predate a
 * stored type. Centralizes the disambiguation so a workspace name registered on more than one SCM
 * — e.g. a GitHub org and a Bitbucket workspace sharing a name — always brokers the RIGHT provider.
 *
 * <p>The review/credential path already resolved this way; the conversation saga, self-loop guard,
 * and thread-refetch endpoint now share this one path instead of resolving by workspace alone
 * (which picked the oldest provider and cross-wired SCMs).
 */
@ApplicationScoped
public class ReviewProviderResolver {

    @Inject
    ProviderRegistry providers;

    @Inject
    ReviewProjection projection;

    /** The enabled provider for the review, disambiguated by its stored SCM type. */
    public Optional<ScmProvider> resolveForReview(String reviewId) {
        RepoRef repo = ReviewIds.parse(reviewId).repo();
        String type = projection.providerTypeOf(reviewId).filter(t -> !t.isBlank()).orElse(null);
        return type == null
                ? providers.resolveByWorkspace(repo.workspace())
                : providers.resolve(type, repo.workspace());
    }
}
