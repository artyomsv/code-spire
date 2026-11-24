package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.StartReview;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Where a review of a new head starts: at once, or after its checks finish (V121,
 * docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md). Called where the aggregate has just accepted the
 * run (a pull request opened or updated, a re-run), in place of sending the run's first command.
 *
 * <p>With "after checks finish" nothing is sent: the wait is recorded under the run's round and the review shows
 * "Waiting for checks". The aggregate stays REVIEWING on the head, so the existing rules still hold: a new commit
 * supersedes the wait, a close cancels it, and a redelivered event for the head is a no-op. {@link ChecksWaitResolver}
 * ends the wait. A round that cannot be read starts the review at once rather than waiting on a guess.
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

    @Inject
    ReviewRuns runs;

    /** Sends {@code fetch}, or records the review as waiting for the checks on {@code fetch.commit()}. */
    public void begin(FetchDiff fetch) {
        String reviewId = fetch.reviewId();
        Optional<ChecksWait> wait = waitingRepository(reviewId).flatMap(repository -> waitFor(fetch, repository));
        if (wait.isEmpty()) {
            waits.clear(reviewId);
            commands.emit(fetch);
            return;
        }
        waits.startWaiting(wait.orElseThrow());
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

    /** The wait of this run; empty when its round cannot be read, so the review starts at once. */
    private Optional<ChecksWait> waitFor(FetchDiff fetch, UUID repository) {
        int round = runs.roundOrUnknown(fetch.reviewId());
        if (round == ReviewRuns.UNKNOWN_RUN) return Optional.empty();
        return Optional.of(ChecksWait.waiting(new ChecksWait.Run(fetch.reviewId(), fetch.commit(), round), repository,
                fetch.untrustedPullRequest()));
    }
}
