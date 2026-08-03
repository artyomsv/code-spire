package dev.codespire.orchestrator.pipeline;

import io.quarkus.test.security.TestSecurity;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scheduled-retry contract against a real datasource: a retry becomes visible only once it is due,
 * and the claim that reads it also clears it — so two sweeps (or two orchestrator replicas) cannot both
 * dispatch the same attempt. That single-dispatch guarantee is the whole reason the backoff is a row and
 * not a {@code Thread.sleep}, so it is worth asserting rather than assuming.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ReviewRetryScheduleIT {

    @Inject
    ReviewProjection projection;

    private String seedReview(long prId) {
        RepoRef repo = new RepoRef("retry-ws", "retry-repo");
        String reviewId = ReviewIds.reviewId(repo, prId);
        projection.registerHeader(reviewId, repo, prId, "Retry", "alice", "acct-1",
                "feature", "main", "cafe123", "https://example/pr/" + prId, "github", "reviewing",
                ReviewProjection.STAGE_DIFF);
        return reviewId;
    }

    @Test
    void aRetryIsInvisibleUntilItComesDue() {
        String reviewId = seedReview(9001L);
        projection.scheduleRetry(reviewId, 2, "waiting", Instant.now().plusSeconds(3600));

        assertFalse(projection.claimDueRetries(Instant.now()).contains(reviewId),
                "not due yet — an early sweep must leave it alone");
        assertTrue(projection.claimDueRetries(Instant.now().plusSeconds(7200)).contains(reviewId),
                "claimable once the due time has passed");
    }

    @Test
    void onlyOneClaimWinsSoAnAttemptCannotBeDispatchedTwice() {
        String reviewId = seedReview(9002L);
        projection.scheduleRetry(reviewId, 2, "waiting", Instant.now().minusSeconds(1));

        List<String> first = projection.claimDueRetries(Instant.now());
        List<String> second = projection.claimDueRetries(Instant.now());

        assertTrue(first.contains(reviewId), "the first sweep claims it");
        assertFalse(second.contains(reviewId), "the second finds nothing — the claim cleared the due time");
    }

    @Test
    void aCancelledRetryIsNeverClaimed() {
        String reviewId = seedReview(9003L);
        projection.scheduleRetry(reviewId, 2, "waiting", Instant.now().minusSeconds(1));
        projection.clearScheduledRetry(reviewId);

        assertFalse(projection.claimDueRetries(Instant.now()).contains(reviewId),
                "a run that went terminal before its retry came due must not be resurrected");
    }

    @Test
    void aFailedDispatchCanBePutBackOnTheClock() {
        String reviewId = seedReview(9004L);
        projection.scheduleRetry(reviewId, 2, "waiting", Instant.now().minusSeconds(1));
        assertTrue(projection.claimDueRetries(Instant.now()).contains(reviewId));

        // The claim already cleared the due time, so a dispatch failing afterwards would otherwise leave
        // the review waiting on a retry nobody sends.
        projection.rescheduleRetry(reviewId, Instant.now().minusSeconds(1));
        assertTrue(projection.claimDueRetries(Instant.now()).contains(reviewId), "claimable again");
    }

    @Test
    void schedulingKeepsTheRunInReviewingAndBumpsTheAttempt() {
        String reviewId = seedReview(9005L);
        projection.scheduleRetry(reviewId, 4, "Transient failure at generate — retrying in 20s (attempt 4/5).",
                Instant.now().plusSeconds(20));

        assertEquals(4, projection.currentAttempt(reviewId), "the attempt counter moved with the schedule");
    }
}
