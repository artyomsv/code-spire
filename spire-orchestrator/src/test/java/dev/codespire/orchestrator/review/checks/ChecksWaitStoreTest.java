package dev.codespire.orchestrator.review.checks;

import dev.codespire.orchestrator.review.checks.ChecksWait.Stage;
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

/**
 * The wait table's SQL against the real schema (V121): one claim per run, round-guarded completion, the timeout hold,
 * and the sweep's claim of due and unfinished rows. Sequential calls: these prove the SQL predicates, not concurrency.
 */
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
    void aRunIsClaimedOnceAndOnlyWhileWaiting() {
        ChecksWait wait = wait(HEAD, 1, Instant.now());
        store.startWaiting(wait);
        assertTrue(store.isWaiting(reviewId));
        assertFalse(store.claim(wait(HEAD, 2, Instant.now()).at(Stage.STARTING)), "another run's claim is refused");
        assertTrue(store.claim(wait.at(Stage.STARTING)));
        assertFalse(store.claim(wait.skipping("TEST-build")), "a second resolver finds the run claimed");
        assertFalse(store.isWaiting(reviewId));
        store.finish(wait.at(Stage.STARTING));
        assertTrue(store.waitingOn(repositoryId, HEAD).isEmpty());
    }

    /** A newer run's wait replaces the row; the older run's completion and drop leave it alone. */
    @Test
    void anOlderRunNeverTouchesANewerRunsWait() {
        ChecksWait first = wait(HEAD, 1, Instant.now());
        store.startWaiting(first);
        store.claim(first.at(Stage.STARTING));
        store.startWaiting(wait(NEXT_HEAD, 2, Instant.now()));
        store.finish(first.at(Stage.STARTING));
        store.drop(first);
        assertEquals(List.of(NEXT_HEAD), store.waitingOn(repositoryId, NEXT_HEAD).stream().map(ChecksWait::head).toList());
        assertTrue(store.waitingOn(UUID.randomUUID(), NEXT_HEAD).isEmpty(), "a wake-up reads its own repository only");
    }

    @Test
    void aTimedOutStartHoldsItsHeadFromTheClaimOn() {
        ChecksWait wait = wait(HEAD, 1, Instant.now());
        store.startWaiting(wait);
        assertTrue(store.claim(wait.at(Stage.STARTING_UNFINISHED)));
        assertTrue(store.startedUnfinished(reviewId, HEAD), "held while the start is pending");
        store.finish(wait.at(Stage.STARTING_UNFINISHED));
        assertTrue(store.startedUnfinished(reviewId, HEAD));
        assertFalse(store.startedUnfinished(reviewId, NEXT_HEAD));
        store.clear(reviewId);
        assertFalse(store.startedUnfinished(reviewId, HEAD), "a start at once drops the hold");
    }

    /** Due after the grace period; the claim pushes the next read out, so a second sweep claims nothing. */
    @Test
    void theSweepClaimsADueWaitOnce() {
        store.startWaiting(wait(HEAD, 1, Instant.now().minus(ChecksWaitDecision.GRACE).minusSeconds(1)));
        List<ChecksWait> claimed = mine(store.claimDue(1000));
        assertEquals(1, claimed.size());
        assertTrue(claimed.getFirst().untrusted());
        assertEquals(Stage.WAITING, claimed.getFirst().stage());
        assertTrue(mine(store.claimDue(1000)).isEmpty());
    }

    @Test
    void aWaitInsideItsGracePeriodIsNotDue() {
        store.startWaiting(wait(HEAD, 1, Instant.now()));
        assertTrue(mine(store.claimDue(1000)).isEmpty());
    }

    /** A claimed skip keeps its failed checks for the sweep that finishes it after a restart. */
    @Test
    void aClaimedSkipCarriesItsFailedChecks() throws Exception {
        ChecksWait wait = wait(HEAD, 1, Instant.now());
        store.startWaiting(wait);
        assertTrue(store.claim(wait.skipping("TEST-build, TEST-lint")));
        execute("UPDATE review_checks_wait SET next_read_at = now() - interval '1 second' WHERE review_id = ?", reviewId);
        ChecksWait resumed = mine(store.claimDue(1000)).getFirst();
        assertEquals(Stage.SKIPPING, resumed.stage());
        assertEquals("TEST-build, TEST-lint", resumed.failedChecks());
        assertEquals(1, resumed.round());
    }

    private List<ChecksWait> mine(List<ChecksWait> claimed) {
        return claimed.stream().filter(w -> w.reviewId().equals(reviewId)).toList();
    }

    private ChecksWait wait(String head, int round, Instant since) {
        return new ChecksWait(reviewId, repositoryId, head, round, true, since, Stage.WAITING, null);
    }

    private void execute(String sql, Object... parameters) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            statement.executeUpdate();
        }
    }
}
