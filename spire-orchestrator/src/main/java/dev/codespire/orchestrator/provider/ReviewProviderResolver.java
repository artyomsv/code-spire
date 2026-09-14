package dev.codespire.orchestrator.provider;

import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Resolves the selected reviewer account through the review's persisted repository id. Unmapped
 * history has no dispatch credential until its repository mapping is repaired explicitly. Review,
 * conversation, self-loop, rerun and thread-refetch callers share this resolution path.
 */
@ApplicationScoped
public class ReviewProviderResolver {

    @Inject
    dev.codespire.orchestrator.repository.RepositoryAccounts accounts;

    @Inject
    ReviewProjection projection;

    /** The usable reviewer account selected on the review's repository. */
    public Optional<ScmProvider> resolveForReview(String reviewId) {
        return projection.repositoryIdOf(reviewId)
                .flatMap(id -> accounts.resolve(id, ProviderRole.REVIEWER));
    }
}
