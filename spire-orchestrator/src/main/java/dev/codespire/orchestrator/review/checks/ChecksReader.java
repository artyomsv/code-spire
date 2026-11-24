package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Reads a waiting head's checks through the review's REVIEWER account, with the land path's read-only pull request
 * reader ({@link ProviderClients#pullRequestReader}): the pull request's head plus its commit statuses and check runs,
 * reduced by {@code GitHubCheckReduction}. The reviewer's token is never asked to merge. A forge without that reader
 * (GitLab, Bitbucket) has no check read, so its reviews start at once.
 */
@ApplicationScoped
public class ChecksReader {

    private static final Logger LOG = Logger.getLogger(ChecksReader.class);

    @Inject
    ReviewProviderResolver providers;

    @Inject
    ProviderClients clients;

    /** Whether this review's forge can report its checks; false starts the review at once. */
    public boolean available(String reviewId) {
        return reader(reviewId).map(PullRequestLander::available).orElse(false);
    }

    /** One read of the wait's head; {@link ChecksVerdict#UNREADABLE} when the forge or the account cannot answer. */
    public ChecksVerdict read(ChecksWait wait) {
        Optional<PullRequestLander> reader = reader(wait.reviewId()).filter(PullRequestLander::available);
        if (reader.isEmpty()) return ChecksVerdict.UNREADABLE;
        ReviewIds.Parsed review = ReviewIds.parse(wait.reviewId());
        try {
            return ChecksVerdict.of(reader.orElseThrow().read(review.repo(), review.prId()), wait.head());
        } catch (RuntimeException unreadable) {
            LOG.warnf(unreadable, "review %s: the checks on %s could not be read; the wait goes on", wait.reviewId(),
                    wait.head());
            return ChecksVerdict.UNREADABLE;
        }
    }

    private Optional<PullRequestLander> reader(String reviewId) {
        return providers.resolveForReview(reviewId).map(clients::pullRequestReader);
    }
}
