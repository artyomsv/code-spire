package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * What ends a wait for checks: the review starts (green, no checks, or the timeout) or is skipped (red). Each outcome
 * takes the wait first, and only the caller that took it acts, so a duplicate wake-up or a sweep racing a webhook
 * starts one review and labels once.
 */
@ApplicationScoped
public class ChecksWaitOutcomes {

    private static final Logger LOG = Logger.getLogger(ChecksWaitOutcomes.class);

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

    /** Every check finished green, or the head has none: the review starts as it would have at once. */
    public void start(ChecksWait wait) {
        Optional<String> credential = credentialFor(wait);
        if (credential.isEmpty() || !waits.take(wait.reviewId(), wait.head())) return;
        send(wait, credential.get(), "ChecksPassed", "checks finished on " + wait.head() + "; the review starts");
    }

    /** Checks still pending after the timeout: the review starts, and the kept row keeps it from approving. */
    public void startUnfinished(ChecksWait wait) {
        Optional<String> credential = credentialFor(wait);
        if (credential.isEmpty() || !waits.markTimedOut(wait.reviewId(), wait.head())) return;
        send(wait, credential.get(), "ChecksTimedOut", "checks on " + wait.head() + " still pending after "
                + ChecksWaitDecision.TIMEOUT.toMinutes() + " minutes; the review starts and will not approve");
    }

    /**
     * A check failed: no review runs and nothing is charged. The aggregate ends the head's run first, guarded by the
     * head ({@code SkipReview}), so a skip never ends a newer commit's run and is a no-op for a second caller; the
     * review is never left REVIEWING with its wait gone.
     */
    public void skip(ChecksWait wait, List<String> failed) {
        boolean ended = !lifecycle.handle(wait.reviewId(),
                new RecordCommand.SkipReview(wait.head(), ChecksWaitStatus.CHECKS_FAILED)).isEmpty();
        if (!waits.take(wait.reviewId(), wait.head()) || !ended) return;
        String names = failed.isEmpty() ? "a check" : String.join(", ", failed);
        projection.setNote(wait.reviewId(), "Checks failed on " + wait.head() + ": " + names
                + ". No review ran and nothing was charged; a new commit waits for its checks again.");
        projection.appendEvent(wait.reviewId(), "integration", "ChecksFailed", "failed on " + wait.head() + ": " + names);
        LOG.infof("review %s: checks failed on %s (%s); the review is skipped", wait.reviewId(), wait.head(), names);
        if (repositories.verdictLabels(wait.repositoryId()).orElse(false)) {
            labelFailed(wait);
        }
    }

    private void labelFailed(ChecksWait wait) {
        ReviewIds.Parsed review = ReviewIds.parse(wait.reviewId());
        credentialFor(wait).ifPresent(credential -> commands.emit(new ActionCommand.LabelChecksFailed(
                wait.reviewId(), review.repo(), review.prId(), wait.head(), credential)));
    }

    /**
     * Sends the run's first command. A failed send puts the wait back, so the next read tries again rather than leaving
     * a review "reviewing" with nothing on the bus.
     */
    private void send(ChecksWait wait, String credential, String type, String detail) {
        ReviewIds.Parsed review = ReviewIds.parse(wait.reviewId());
        try {
            commands.emit(new ActionCommand.FetchDiff(wait.reviewId(), review.repo(), review.prId(), wait.head(),
                    credential, wait.untrusted()));
        } catch (RuntimeException failure) {
            waits.startWaiting(wait);
            throw failure;
        }
        projection.updateStatus(wait.reviewId(), "reviewing", ReviewProjection.STAGE_DIFF);
        projection.appendEvent(wait.reviewId(), "integration", type, detail);
        LOG.infof("review %s: %s", wait.reviewId(), detail);
    }

    private Optional<String> credentialFor(ChecksWait wait) {
        Optional<String> credential = credentials.packForReview(wait.reviewId());
        if (credential.isEmpty()) {
            LOG.warnf("review %s: no usable reviewer account, so the wait on %s cannot end; select an enabled Reviewer"
                    + " under Settings -> Repositories", wait.reviewId(), wait.head());
        }
        return credential;
    }
}
