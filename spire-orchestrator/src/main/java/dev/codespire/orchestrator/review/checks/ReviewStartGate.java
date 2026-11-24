package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.StartReview;
import dev.codespire.orchestrator.review.checks.ChecksWaitStore.PendingAdmission;
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
 * <p>With "after checks finish" nothing is sent. The run's identity is read from one fold of the aggregate (its
 * current head and round, {@link ReviewState#round}); a run the aggregate no longer reviews is stale and registers
 * nothing, and the store keeps a later run's row against a late registration. A fold that fails keeps the gate
 * closed: the review shows "Waiting for checks" with no row, and the sweep admits it again ({@link #admit}). Nothing
 * here ever starts a review at once on a failure. The aggregate stays REVIEWING on the head, so a new commit
 * supersedes the wait, a close cancels it, and a redelivered event for the head is a no-op.
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
    ReviewLifecycleService lifecycle;

    @Inject
    SkippedRunReceipts receipts;

    /** Sends {@code fetch}, or admits the review to wait for the checks on {@code fetch.commit()}. */
    public void begin(FetchDiff fetch) {
        String reviewId = fetch.reviewId();
        supersedeLabels(reviewId);
        Optional<UUID> repository = waitingRepository(reviewId);
        if (repository.isEmpty()) {
            commands.emit(fetch);
            clearEarlierRuns(reviewId);
            return;
        }
        admit(new PendingAdmission(reviewId, repository.orElseThrow(), fetch.commit(), fetch.untrustedPullRequest()));
    }

    /** Records the wait of the admitted run; also the sweep's retry of an admission whose identity was not read. */
    public void admit(PendingAdmission admission) {
        Optional<ChecksWait> wait;
        try {
            wait = identify(admission);
        } catch (RuntimeException unreadable) {
            LOG.warnf(unreadable, "review %s: the run waiting on %s could not be identified; it stays waiting and the"
                    + " sweep admits it again", admission.reviewId(), admission.head());
            markWaiting(admission, "ChecksWaitPending");
            return;
        }
        if (wait.isEmpty()) {
            LOG.infof("review %s: no wait for %s, which the review no longer runs", admission.reviewId(), admission.head());
            return;
        }
        waits.startWaiting(wait.orElseThrow());
        markWaiting(admission, "ChecksWaitStarted");
        LOG.infof("review %s: waiting for the checks on %s before reviewing", admission.reviewId(), admission.head());
    }

    /** The current run's wait when it is the admitted head's; empty for a run a newer one replaced. */
    private Optional<ChecksWait> identify(PendingAdmission admission) {
        ReviewState state = lifecycle.currentState(admission.reviewId());
        if (!state.isReviewing() || !admission.head().equals(state.currentCommit())) return Optional.empty();
        return Optional.of(ChecksWait.waiting(new ChecksWait.Run(admission.reviewId(), admission.head(), state.round()),
                admission.repositoryId(), admission.untrusted()));
    }

    private void markWaiting(PendingAdmission admission, String type) {
        projection.updateStatus(admission.reviewId(), ChecksWaitStatus.WAITING);
        projection.appendEvent(admission.reviewId(), "integration", type, "waiting for checks on " + admission.head());
    }

    /** The repository when this review waits; empty when it starts at once (setting, or a forge with no check read). */
    private Optional<UUID> waitingRepository(String reviewId) {
        return projection.repositoryIdOf(reviewId)
                .filter(id -> setting.of(id) == StartReview.AFTER_CHECKS)
                .filter(id -> reader.available(reviewId));
    }

    /** A run started at once leaves no earlier run's wait or hold; one missed here is dropped as stale when read. */
    private void clearEarlierRuns(String reviewId) {
        try {
            waits.clearBefore(reviewId, lifecycle.currentState(reviewId).round());
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "review %s: an earlier run's check wait could not be cleared", reviewId);
        }
    }

    /** A new run of the review closes the earlier runs' open ci-failed receipts; a miss only weakens that fence. */
    private void supersedeLabels(String reviewId) {
        try {
            receipts.supersede(reviewId);
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "review %s: earlier skipped runs' label receipts could not be closed", reviewId);
        }
    }
}
