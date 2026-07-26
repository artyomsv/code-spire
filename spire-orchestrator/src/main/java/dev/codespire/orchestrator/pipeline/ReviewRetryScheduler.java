package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Dispatches review retries once their backoff has elapsed.
 *
 * <p>{@link ResultSaga} cannot wait for the delay itself — its consumer is {@code @Blocking} and ordered
 * per partition, so sleeping would stall every other review on that partition. It therefore records WHEN
 * the next attempt is due and returns; this sweeper turns a due row back into a {@code FetchDiff}.
 *
 * <p>Because the wait lives in the database rather than on a thread, a retry survives an orchestrator
 * restart, and {@link ReviewProjection#claimDueRetries} clears the due time in the same statement that
 * reads it — so overlapping sweeps, or replicas, cannot dispatch one attempt twice.
 */
@ApplicationScoped
public class ReviewRetryScheduler {

    private static final Logger LOG = Logger.getLogger(ReviewRetryScheduler.class);

    @Inject
    ReviewProjection projection;

    @Inject
    WorkerCredentials workerCredentials;

    @Inject
    CommandsEmitter commands;

    /**
     * Every 5s: fine-grained enough for a 5s minimum backoff, and the query is a partial-index lookup on
     * "anything due?", which is empty almost always. {@code concurrentExecution = SKIP} keeps a slow
     * sweep from overlapping itself.
     */
    @Scheduled(every = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void dispatchDueRetries() {
        List<String> due = projection.claimDueRetries(Instant.now());
        for (String reviewId : due) {
            dispatch(reviewId);
        }
    }

    /**
     * One claimed review. A failure here must not abandon the rest of the batch, and it must not leave the
     * review waiting on a retry that will never arrive: the claim already cleared {@code retry_at}, so the
     * run would sit in {@code reviewing} forever. Re-scheduling one tick later lets the next sweep try
     * again, bounded by the attempt budget the saga already applied.
     */
    private void dispatch(String reviewId) {
        try {
            ReviewIds.Parsed parsed = ReviewIds.parse(reviewId);
            Optional<String> commit = projection.commitOf(reviewId);
            Optional<String> credential = workerCredentials.packForReview(reviewId);
            if (commit.isEmpty() || credential.isEmpty()) {
                // The provider was removed, or the row lost its commit: nothing to retry against. Leave
                // it alone — the operator can re-run, and inventing a dispatch here would fail anyway.
                LOG.warnf("Skipping due retry for %s — %s", reviewId,
                        commit.isEmpty() ? "no current commit on the review" : "no provider credential");
                return;
            }
            LOG.infof("Dispatching due retry for %s at commit %s", reviewId, commit.get());
            commands.emit(new ActionCommand.FetchDiff(
                    reviewId, parsed.repo(), parsed.prId(), commit.get(), credential.get()));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Could not dispatch the due retry for %s — re-scheduling for the next sweep", reviewId);
            projection.rescheduleRetry(reviewId, Instant.now().plusSeconds(5));
        }
    }
}
