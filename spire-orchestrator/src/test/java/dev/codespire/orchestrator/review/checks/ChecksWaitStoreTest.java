package dev.codespire.orchestrator.review.checks;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wait table's SQL against the real schema (V121): one take per wait, the timeout hold, and the sweep's claim. */
@QuarkusTest
class ChecksWaitStoreTest {

    private static final String HEAD = "a".repeat(40);
    private static final String NEXT_HEAD = "b".repeat(40);

    @Inject ChecksWaitStore store;
    @Inject DataSource dataSource;

    private final String workspace = "TEST-" + UUID.randomUUID();
    private final String reviewId = "github:" + workspace + "/TEST-repo#7";
    private final UUID repositoryId = UUID.randomUUID();

    @BeforeEach
    void seedReview() throws Exception {
        execute("INSERT INTO review_status (review_id, workspace, slug, pr_id, status) VALUES (?, ?, 'TEST-repo', 7, ?)",
                reviewId, workspace, ChecksWaitStatus.WAITING);
    }

    @AfterEach
    void removeReview() throws Exception {
        execute("DELETE FROM review_status WHERE workspace = ?", workspace);
    }

    @Test
    void aWaitIsTakenOnceAndOnlyOnItsHead() {
        store.startWaiting(wait(HEAD, Instant.now()));
        assertTrue(store.isWaiting(reviewId));
        assertFalse(store.take(reviewId, NEXT_HEAD), "another head's wait is not this one");
        assertTrue(store.take(reviewId, HEAD));
        assertFalse(store.take(reviewId, HEAD), "a duplicate wake-up finds nothing to take");
        assertFalse(store.isWaiting(reviewId));
    }

    @Test
    void aNewHeadReplacesTheWait() {
        store.startWaiting(wait(HEAD, Instant.now()));
        store.startWaiting(wait(NEXT_HEAD, Instant.now()));
        assertTrue(store.waitingOn(repositoryId, HEAD).isEmpty());
        assertEquals(List.of(NEXT_HEAD), store.waitingOn(repositoryId, NEXT_HEAD).stream().map(ChecksWait::head).toList());
        assertTrue(store.waitingOn(UUID.randomUUID(), NEXT_HEAD).isEmpty(), "a wake-up reads its own repository only");
    }

    @Test
    void theTimeoutKeepsTheRowAsAHoldOnItsHead() {
        store.startWaiting(wait(HEAD, Instant.now()));
        assertTrue(store.markTimedOut(reviewId, HEAD));
        assertFalse(store.markTimedOut(reviewId, HEAD));
        assertTrue(store.startedUnfinished(reviewId, HEAD));
        assertFalse(store.startedUnfinished(reviewId, NEXT_HEAD));
        assertFalse(store.isWaiting(reviewId));
        store.clear(reviewId);
        assertFalse(store.startedUnfinished(reviewId, HEAD), "a start at once drops the hold");
    }

    /** Due after the grace period; the claim pushes the next read out, so a second sweep claims nothing. */
    @Test
    void theSweepClaimsADueWaitOnce() {
        store.startWaiting(wait(HEAD, Instant.now().minus(ChecksWaitDecision.GRACE).minusSeconds(1)));
        List<ChecksWait> claimed = store.claimDue(1000).stream().filter(w -> w.reviewId().equals(reviewId)).toList();
        assertEquals(1, claimed.size());
        assertTrue(claimed.getFirst().untrusted());
        assertTrue(store.claimDue(1000).stream().noneMatch(w -> w.reviewId().equals(reviewId)));
    }

    @Test
    void aWaitInsideItsGracePeriodIsNotDue() {
        store.startWaiting(wait(HEAD, Instant.now()));
        assertTrue(store.claimDue(1000).stream().noneMatch(w -> w.reviewId().equals(reviewId)));
    }

    private ChecksWait wait(String head, Instant since) {
        return new ChecksWait(reviewId, repositoryId, head, true, since);
    }

    private void execute(String sql, Object... parameters) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            statement.executeUpdate();
        }
    }
}
