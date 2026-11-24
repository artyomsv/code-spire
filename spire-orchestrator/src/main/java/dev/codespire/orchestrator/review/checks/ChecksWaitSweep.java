package dev.codespire.orchestrator.review.checks;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The fallback read of review waits for CI: every wait is read 60 s after it starts (the grace read for a head with no
 * checks) and then every 5 minutes, so a lost or never-configured {@code check_suite}/{@code status} webhook only
 * delays a review, and the 60-minute timeout is noticed. It also finishes an outcome a process claimed and did not
 * complete (a crash between the claim and the broker ack), and admits again a review whose run could not be identified
 * when it was admitted. The due time lives in the database, so a restart resumes it.
 */
@ApplicationScoped
public class ChecksWaitSweep {

    private static final Logger LOG = Logger.getLogger(ChecksWaitSweep.class);
    /** Reads per sweep; the rest stay due for the next one. */
    static final int BATCH = 20;

    @Inject
    ChecksWaitStore waits;

    @Inject
    ChecksWaitResolver resolver;

    @Inject
    ReviewStartGate gate;

    @Scheduled(every = "${spire.review.checks-sweep-interval:30s}", delayed = "30s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        admitPending();
        for (ChecksWait wait : waits.claimDue(BATCH)) {
            try {
                resolver.resume(wait);
            } catch (RuntimeException failure) {
                // Claimed, so its next read is already 5 minutes out: one failure never stops the rest of the batch.
                LOG.warnf(failure, "review %s: the wait on %s could not be resolved; it is read again later",
                        wait.reviewId(), wait.head());
            }
        }
    }

    private void admitPending() {
        for (ChecksWaitStore.PendingAdmission pending : waits.pendingAdmissions(BATCH)) {
            try {
                gate.admit(pending);
            } catch (RuntimeException failure) {
                LOG.warnf(failure, "review %s: the admission on %s could not be recorded; the next sweep tries again",
                        pending.reviewId(), pending.head());
            }
        }
    }
}
