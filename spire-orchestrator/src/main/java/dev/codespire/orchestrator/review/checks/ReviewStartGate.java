package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.StartReview;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a review of a new head starts: at once, or after its checks finish (V121,
 * docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md). Called where the aggregate has just accepted the
 * run (a pull request opened or updated, a re-run), in place of sending the run's first command.
 *
 * <p>With "after checks finish" nothing is sent: the wait is recorded and the review shows "Waiting for checks". The
 * aggregate stays REVIEWING on the head, so the existing rules still hold: a new commit supersedes the wait, a close
 * cancels it, and a redelivered event for the head is a no-op. {@link ChecksWaitResolver} ends the wait.
 */
@ApplicationScoped
public class ReviewStartGate {

    private static final Logger LOG = Logger.getLogger(ReviewStartGate.class);

    @Inject
    ReviewProjection projection;

    @Inject
    StartReviewSetting setting;

    @Inject
    ChecksReader reader;

    @Inject
    ChecksWaitStore waits;

    @Inject
    CommandsEmitter commands;

    /** Sends {@code fetch}, or records the review as waiting for the checks on {@code fetch.commit()}. */
    public void begin(FetchDiff fetch) {
        String reviewId = fetch.reviewId();
        Optional<UUID> repository = waitingRepository(reviewId);
        if (repository.isEmpty()) {
            waits.clear(reviewId);
            commands.emit(fetch);
            return;
        }
        waits.startWaiting(new ChecksWait(reviewId, repository.get(), fetch.commit(), fetch.untrustedPullRequest(),
                Instant.now()));
        projection.updateStatus(reviewId, ChecksWaitStatus.WAITING);
        projection.appendEvent(reviewId, "integration", "ChecksWaitStarted", "waiting for checks on " + fetch.commit());
        LOG.infof("review %s: waiting for the checks on %s before reviewing", reviewId, fetch.commit());
    }

    /** The repository when this review waits; empty when it starts at once (setting, or a forge with no check read). */
    private Optional<UUID> waitingRepository(String reviewId) {
        return projection.repositoryIdOf(reviewId)
                .filter(id -> setting.of(id) == StartReview.AFTER_CHECKS)
                .filter(id -> reader.available(reviewId));
    }
}
