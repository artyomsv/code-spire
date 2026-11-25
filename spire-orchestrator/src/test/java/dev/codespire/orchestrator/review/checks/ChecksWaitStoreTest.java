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
        appendRequest(1);
    }

    @AfterEach
    void removeReview() throws Exception {
        execute("DELETE FROM review_status WHERE workspace = ?", workspace);
        execute("DELETE FROM event_log WHERE stream_id = ?", reviewId);
    }

    /** The aggregate accepted another run: its ReviewRequested, which the current round counts. */
    private void appendRequest(int sequence) throws Exception {
        execute("INSERT INTO event_log (event_id, stream_id, sequence, event_type, payload, occurred_at)"
                + " VALUES (?, ?, ?, 'ReviewRequested', '\\x7b7d'::bytea, now())", UUID.randomUUID(), reviewId,
                (long) sequence);
    }

    /**
     * A waiting run r was admitted and paused before begin; the repository switched to "Immediately" and r+1 re-ran the
     * same head, which records no row. Resuming, r's readying, claim and status write all find r no longer current.
     */
    @Test
    void aRunNoLongerCurrentIsNeverReadiedClaimedOrShown() throws Exception {
        ChecksWait admitted = wait(HEAD, 1, Instant.now()).at(Stage.ADMITTED);
        store.register(admitted);
        appendRequest(2);
        execute("UPDATE review_status SET status = 'reviewing', run_token = 'TEST-immediate' WHERE review_id = ?",
                reviewId);
        String header = header();
        assertFalse(store.ready(admitted.run()), "begin of the paused run");
        assertEquals(header, header(), "the immediate run's status, head and token survive");
        assertEquals("admitted:1:null", row());
        execute("UPDATE review_checks_wait SET state = 'waiting' WHERE review_id = ?", reviewId);
        assertFalse(store.claim(admitted.at(Stage.STARTING)), "no outcome for a run that is not current");
        assertFalse(store.markWaiting(reviewId, 1));
        assertEquals(header, header());
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
        assertEquals("started:1:null", row(), "a completed start stays as the run's admission record");
        assertEquals(1, store.admittedRound(reviewId));
    }

    /** An admitted run is unreadable and unclaimable until ready, and only its own round can make it ready. */
    @Test
    void anAdmittedRunIsReadOnlyOnceReady() {
        ChecksWait wait = wait(HEAD, 1, Instant.now());
        store.register(wait.at(Stage.ADMITTED));
        assertTrue(store.waitingOn(repositoryId, HEAD).isEmpty());
        assertFalse(store.claim(wait.at(Stage.STARTING)));
        assertFalse(store.markWaiting(reviewId, 1));
        assertFalse(store.ready(new ChecksWait.Run(reviewId, HEAD, 2)));
        assertFalse(store.ready(new ChecksWait.Run(reviewId, NEXT_HEAD, 1)));
        assertTrue(store.ready(new ChecksWait.Run(reviewId, HEAD, 1)));
        assertFalse(store.ready(new ChecksWait.Run(reviewId, HEAD, 1)), "once");
        assertEquals(List.of(HEAD), store.waitingOn(repositoryId, HEAD).stream().map(ChecksWait::head).toList());
    }

    /**
     * The recovery writer paused past its read: the caller wrote the run's header (round 1, its token) and made it ready
     * (status), a red read skipped it and the skip was projected. The recovery then resumes: its readying matches no row
     * and writes no header, so the status stays skipped and the token is the caller's.
     */
    @Test
    void aPausedRecoveryNeverRewritesTheHeaderOfARunThatMovedOn() throws Exception {
        ChecksWait admitted = wait(HEAD, 1, Instant.now()).at(Stage.ADMITTED);
        store.register(admitted);
        execute("UPDATE review_status SET status = 'reviewing', commit_sha = ?, run_token = 'TEST-caller', run_round = 1"
                + " WHERE review_id = ?", HEAD, reviewId);
        assertTrue(store.ready(admitted.run()), "the caller's begin");
        String readied = header();
        assertTrue(readied.startsWith(ChecksWaitStatus.WAITING + ":" + HEAD + ":"), readied);
        assertTrue(readied.endsWith(":TEST-caller"), "the caller initialised round 1, so its token stays");
        ChecksWait waiting = admitted.at(Stage.WAITING);
        assertTrue(store.claim(waiting.skipping("TEST-build")));
        store.finish(waiting.skipping("TEST-build"));
        execute("UPDATE review_status SET status = 'skipped' WHERE review_id = ?", reviewId);
        String skipped = header();
        assertFalse(store.ready(admitted.run()), "the paused recovery");
        assertEquals(skipped, header(), "status, head and token untouched");
        assertTrue(skipped.startsWith("skipped:"), skipped);
    }

    /** A newer admission in the gap: an earlier run's readying writes nothing over it. */
    @Test
    void aStaleReadyingNeverTouchesANewerAdmission() throws Exception {
        store.register(wait(HEAD, 1, Instant.now()).at(Stage.ADMITTED));
        store.register(wait(NEXT_HEAD, 2, Instant.now()).at(Stage.ADMITTED));
        execute("UPDATE review_status SET status = 'reviewing', run_token = 'TEST-newer' WHERE review_id = ?", reviewId);
        assertFalse(store.ready(new ChecksWait.Run(reviewId, HEAD, 1)));
        assertEquals("reviewing:", header().substring(0, "reviewing:".length()));
        assertTrue(header().endsWith(":TEST-newer"));
        assertEquals("admitted:2:null", row());
    }

    /** The review's header as {@code status:commit_sha:run_token}. */
    private String header() throws Exception {
        try (java.sql.Connection connection = dataSource.getConnection(); java.sql.PreparedStatement statement =
                connection.prepareStatement("SELECT status, commit_sha, run_token FROM review_status WHERE review_id = ?")) {
            statement.setString(1, reviewId);
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1) + ":" + rows.getString(2) + ":" + rows.getString(3);
            }
        }
    }

    /** The waiting status is written only while that very round waits, never over a later status. */
    @Test
    void theWaitingStatusIsShownOnlyWhileThatRoundWaits() {
        ChecksWait wait = wait(HEAD, 1, Instant.now());
        store.startWaiting(wait);
        assertFalse(store.markWaiting(reviewId, 2), "another round");
        assertTrue(store.markWaiting(reviewId, 1));
        store.claim(wait.at(Stage.STARTING));
        assertFalse(store.markWaiting(reviewId, 1), "its start was claimed");
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
        assertTrue(store.startedUnfinished(new ChecksWait.Run(reviewId, HEAD, 1)), "held while the start is pending");
        store.finish(wait.at(Stage.STARTING_UNFINISHED));
        assertTrue(store.startedUnfinished(new ChecksWait.Run(reviewId, HEAD, 1)));
        assertFalse(store.startedUnfinished(new ChecksWait.Run(reviewId, NEXT_HEAD, 1)));
        assertFalse(store.startedUnfinished(new ChecksWait.Run(reviewId, HEAD, 2)), "a later round of the head is not held");
        store.register(wait(HEAD, 1, Instant.now()).at(Stage.STARTED));
        assertTrue(store.startedUnfinished(new ChecksWait.Run(reviewId, HEAD, 1)), "the same round is never registered again");
        store.register(wait(HEAD, 2, Instant.now()).at(Stage.STARTED));
        assertFalse(store.startedUnfinished(new ChecksWait.Run(reviewId, HEAD, 1)), "a later run started at once replaces the hold");
    }

    /** A late registration of an earlier run never replaces a later run's row. */
    @Test
    void aLateRegistrationOfAnEarlierRunIsRefused() {
        store.startWaiting(wait(NEXT_HEAD, 3, Instant.now()));
        store.startWaiting(wait(HEAD, 1, Instant.now()));
        assertEquals(List.of(NEXT_HEAD), store.waitingOn(repositoryId, NEXT_HEAD).stream().map(ChecksWait::head).toList());
        assertTrue(store.waitingOn(repositoryId, HEAD).isEmpty());
    }

    /** A reader's stale waiting snapshot drops nothing once another resolver claimed the run. */
    @Test
    void aStaleSnapshotNeverDropsAClaim() {
        ChecksWait wait = wait(HEAD, 1, Instant.now());
        store.startWaiting(wait);
        assertTrue(store.claim(wait.skipping("TEST-build")));
        store.drop(wait);
        assertEquals("skipping:1:TEST-build", row(), "the claim, the winner's recovery intent, survives");
        store.drop(wait.skipping("TEST-build"));
        assertEquals("none", row(), "dropping the snapshot as observed removes it");
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

    /** The review's persisted row as {@code state:round:failed_checks}, or {@code none}. */
    private String row() {
        try (java.sql.Connection connection = dataSource.getConnection(); java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT state, round, failed_checks FROM review_checks_wait WHERE review_id = ?")) {
            statement.setString(1, reviewId);
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) + ":" + rows.getInt(2) + ":" + rows.getString(3) : "none";
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private List<ChecksWait> mine(List<ChecksWait> claimed) {
        return claimed.stream().filter(w -> w.reviewId().equals(reviewId)).toList();
    }

    private ChecksWait wait(String head, int round, Instant since) {
        return new ChecksWait(reviewId, repositoryId, head, round, true, since, Stage.WAITING, null);
    }

    private void execute(String sql, Object... parameters) throws Exception {
        try (java.sql.Connection connection = dataSource.getConnection(); java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            statement.executeUpdate();
        }
    }
}
