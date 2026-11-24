package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.review.checks.ChecksWait.Stage;
import dev.codespire.orchestrator.review.run.ReviewRoundLock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * What ends a wait for checks: the review starts (green, no checks, or the timeout) or is skipped (red).
 *
 * <p>Each outcome first claims the waiting run ({@link ChecksWaitStore#claim}); only the claimant acts, so two
 * resolvers (a webhook and the sweep, two webhooks) never both start, never both skip, and a skip never cancels a run
 * another resolver started. The claimed row is the recoverable intent: the effects follow, and the row is completed
 * last, so a process that dies in between leaves the claim for the sweep, which finishes it ({@link #dispatch},
 * {@link #finishSkip}). A restart can therefore send a run's FetchDiff or its label twice, never zero times; both are
 * idempotent downstream (the stale-run guard and the worker's claims, the label's per-run receipt).
 */
@ApplicationScoped
public class ChecksWaitOutcomes {

    private static final Logger LOG = Logger.getLogger(ChecksWaitOutcomes.class);
    private static final String NO_NAMES = "a check";

    @Inject
    ChecksWaitStore waits;

    @Inject
    ReviewLifecycleService lifecycle;

    @Inject
    ReviewProjection projection;

    @Inject
    CommandsEmitter commands;

    @Inject
    WorkerCredentials credentials;

    @Inject
    RepositoryRegistry repositories;

    @Inject
    ReviewRoundLock rounds;

    @Inject
    SkippedRunReceipts receipts;

    /** Every check finished green, or the head has none: the review starts as it would have at once. */
    public void start(ChecksWait wait) {
        claimThenDispatch(wait.at(Stage.STARTING));
    }

    /** Checks still pending after the timeout: the review starts, and the kept row keeps it from approving. */
    public void startUnfinished(ChecksWait wait) {
        claimThenDispatch(wait.at(Stage.STARTING_UNFINISHED));
    }

    /** A check failed: no review runs and nothing is charged. */
    public void skip(ChecksWait wait, List<String> failed) {
        ChecksWait claimed = wait.skipping(failed.isEmpty() ? NO_NAMES : String.join(", ", failed));
        if (waits.claim(claimed)) finishSkip(claimed);
    }

    /**
     * Sends a claimed start's FetchDiff, then completes the claim, under the review's round lock and only while the
     * claimed run is still the review's current run: a re-run of the same head (admitted under the same lock) or a newer
     * commit is never sent an earlier run's start, and the stale claim is dropped. A failed send leaves the claim for
     * the sweep; it is never restored to a waiting row, which could replace a newer run's wait.
     */
    public void dispatch(ChecksWait claimed) {
        Optional<String> credential = credentialFor(claimed);
        if (credential.isEmpty()) return;
        if (!rounds.holding(claimed.reviewId(), () -> sendIfCurrent(claimed, credential.orElseThrow()))) return;
        StartNote note = StartNote.of(claimed);
        projection.updateStatus(claimed.reviewId(), "reviewing", ReviewProjection.STAGE_DIFF);
        projection.appendEvent(claimed.reviewId(), "integration", note.type(), note.detail());
        LOG.infof("review %s: %s", claimed.reviewId(), note.detail());
    }

    /**
     * Ends a claimed skip's run, notes and labels it, then completes the claim. The run is ended under the review's
     * round lock and only while its round is still the review's: a re-run on the same head (which takes the same lock)
     * or a newer commit is never cancelled, and this claim is dropped instead.
     */
    public void finishSkip(ChecksWait claimed) {
        if (!rounds.holding(claimed.reviewId(), () -> endRunAndMark(claimed))) {
            waits.drop(claimed);
            return;
        }
        waits.finish(claimed);
    }

    /** Under the round lock: the run's end and its marks, so a re-run admitted next closes this run's label receipt. */
    private boolean endRunAndMark(ChecksWait claimed) {
        if (!endRun(claimed)) return false;
        String names = claimed.failedChecks() == null ? NO_NAMES : claimed.failedChecks();
        projection.setNote(claimed.reviewId(), "Checks failed on " + claimed.head() + ": " + names
                + ". No review ran and nothing was charged; a new commit waits for its checks again.");
        projection.appendEvent(claimed.reviewId(), "integration", "ChecksFailed",
                "failed on " + claimed.head() + ": " + names);
        LOG.infof("review %s: checks failed on %s (%s); the review is skipped", claimed.reviewId(), claimed.head(), names);
        if (repositories.verdictLabels(claimed.repositoryId()).orElse(false)) labelFailed(claimed);
        return true;
    }

    /** This run's end, which a restart may find already appended; false when a newer run is the review's. */
    private boolean endRun(ChecksWait claimed) {
        ReviewState state = lifecycle.currentState(claimed.reviewId());
        if (state.round() != claimed.round()) return false;
        boolean ended = !lifecycle.handle(claimed.reviewId(), new RecordCommand.SkipReview(claimed.head(),
                claimed.round(), ChecksWaitStatus.CHECKS_FAILED)).isEmpty();
        return ended || state.status() == ReviewState.Status.CANCELLED && claimed.head().equals(state.currentCommit());
    }

    /** Under the round lock: the start is sent only to the run the claim was made for. */
    private boolean sendIfCurrent(ChecksWait claimed, String credential) {
        ReviewState state = lifecycle.currentState(claimed.reviewId());
        if (!state.isReviewing() || !claimed.head().equals(state.currentCommit()) || state.round() != claimed.round()) {
            waits.drop(claimed);
            LOG.infof("review %s: no start for round %d on %s; a newer run is the review's", claimed.reviewId(),
                    claimed.round(), claimed.head());
            return false;
        }
        ReviewIds.Parsed review = ReviewIds.parse(claimed.reviewId());
        commands.emit(new ActionCommand.FetchDiff(claimed.reviewId(), review.repo(), review.prId(), claimed.head(),
                credential, claimed.untrusted()));
        waits.finish(claimed);
        return true;
    }

    private void labelFailed(ChecksWait claimed) {
        ReviewIds.Parsed review = ReviewIds.parse(claimed.reviewId());
        receipts.open(claimed);
        credentialFor(claimed).ifPresent(credential -> commands.emit(new ActionCommand.LabelChecksFailed(
                claimed.reviewId(), review.repo(), review.prId(), claimed.head(), claimed.round(), credential)));
    }

    private Optional<String> credentialFor(ChecksWait wait) {
        Optional<String> credential = credentials.packForReview(wait.reviewId());
        if (credential.isEmpty()) {
            LOG.warnf("review %s: no usable reviewer account, so the wait on %s cannot end; select an enabled Reviewer"
                    + " under Settings -> Repositories", wait.reviewId(), wait.head());
        }
        return credential;
    }

    private void claimThenDispatch(ChecksWait claimed) {
        if (waits.claim(claimed)) dispatch(claimed);
    }

    /** The history line of a start: why the review started. */
    private record StartNote(String type, String detail) {
        static StartNote of(ChecksWait claimed) {
            if (claimed.stage() == Stage.STARTING_UNFINISHED) {
                return new StartNote("ChecksTimedOut", "checks on " + claimed.head() + " still pending after "
                        + ChecksWaitDecision.TIMEOUT.toMinutes() + " minutes; the review starts and will not approve");
            }
            return new StartNote("ChecksPassed", "checks finished on " + claimed.head() + "; the review starts");
        }
    }
}
