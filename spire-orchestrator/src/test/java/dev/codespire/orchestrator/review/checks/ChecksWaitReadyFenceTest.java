package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.review.checks.ChecksWait.Stage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Readying a waiting run and a run's own header write are fenced on the review header row they write (V121,
 * {@code review_status.run_round}), against the real schema: a header a later round wrote, committed before or while
 * the readying runs, is never replaced, and a run's header is initialised once per round, by the caller or by the
 * readying, whichever comes first. A newer run started at once records no wait row, so the header row is the only state
 * the writes share.
 */
@QuarkusTest
class ChecksWaitReadyFenceTest {

    private static final String HEAD = "a".repeat(40);

    @Inject ChecksWaitStore store;
    @Inject ReviewProjection projection;
    @Inject DataSource dataSource;

    private final String workspace = "TEST-" + UUID.randomUUID();
    private final String reviewId = "github:" + workspace + "/TEST-repo#7";
    private final ChecksWait admitted = new ChecksWait(reviewId, UUID.randomUUID(), HEAD, 1, true, Instant.now(),
            Stage.ADMITTED, null);

    @BeforeEach
    void seedReview() throws Exception {
        execute("INSERT INTO review_status (review_id, workspace, slug, pr_id, status, run_token)"
                + " VALUES (?, ?, 'TEST-repo', 7, 'reviewing', 'TEST-first')", reviewId, workspace);
        execute("INSERT INTO event_log (event_id, stream_id, sequence, event_type, payload, occurred_at)"
                + " VALUES (?, ?, 1, 'ReviewRequested', '\\x7b7d'::bytea, now())", UUID.randomUUID(), reviewId);
        store.register(admitted);
    }

    @AfterEach
    void removeReview() throws Exception {
        execute("DELETE FROM review_status WHERE workspace = ?", workspace);
        execute("DELETE FROM event_log WHERE stream_id = ?", reviewId);
    }

    /** A later round's header (run_round 2) whose ReviewRequested the statement does not count still stands. */
    @Test
    void aReadyingNeverReplacesAHeaderALaterRoundWrote() throws Exception {
        execute("UPDATE review_status SET run_token = 'TEST-later', run_round = 2 WHERE review_id = ?", reviewId);
        assertFalse(store.ready(admitted.run()));
        assertEquals("reviewing:TEST-later:2", header());
        assertEquals("admitted", state());
    }

    /** No later round wrote the header: the run is readied and the header takes its round. */
    @Test
    void aReadyingOfTheLatestRoundWritesItsHeader() throws Exception {
        assertTrue(store.ready(admitted.run()));
        assertEquals(ChecksWaitStatus.WAITING, header().split(":")[0]);
        assertEquals("1", header().split(":")[2]);
        assertEquals("waiting", state());
    }

    /**
     * Two real connections: a newer run's header write holds the row, uncommitted, when the paused run's readying
     * starts. The readying waits on the row's lock; once the newer write commits it re-reads the row and writes nothing.
     */
    @Test
    void aReadyingBlockedByANewerRunsHeaderWriteWritesNothing() throws Exception {
        CompletableFuture<Boolean> readying;
        try (Connection newer = dataSource.getConnection()) {
            newer.setAutoCommit(false);
            try (PreparedStatement write = newer.prepareStatement(
                    "UPDATE review_status SET run_token = 'TEST-newer', run_round = 2 WHERE review_id = ?")) {
                write.setString(1, reviewId);
                write.executeUpdate();
            }
            readying = CompletableFuture.supplyAsync(() -> store.ready(admitted.run()));
            assertTrue(waitsOnALock(), "the readying waits for the newer run's header write");
            newer.commit();
            newer.setAutoCommit(true);
        }
        assertFalse(readying.get(30, TimeUnit.SECONDS));
        assertEquals("reviewing:TEST-newer:2", header());
        assertEquals("admitted", state());
    }

    /** The caller's header write came first: the readying shows the run waiting and keeps the caller's token. */
    @Test
    void aReadyingAfterTheCallersHeaderKeepsItsToken() throws Exception {
        registerRunHeader();
        String token = header().split(":")[1];
        assertTrue(store.ready(admitted.run()));
        assertEquals(ChecksWaitStatus.WAITING + ":" + token + ":1", header());
        assertEquals("waiting", state());
    }

    /** The sweep readied round 1 and a red read skipped it: the caller's late header write for round 1 writes nothing. */
    @Test
    void aLateHeaderWriteOfTheSameRoundNeverResetsTheReadiedRun() throws Exception {
        assertTrue(store.ready(admitted.run()));
        execute("UPDATE review_status SET status = 'skipped' WHERE review_id = ?", reviewId);
        String skipped = header();
        registerRunHeader();
        assertEquals(skipped, header());
    }

    /**
     * Two real connections, the same round: the caller's header write for round 1 starts while another connection holds
     * the header row, having readied round 1 (its round, waiting status and token) and then had it skipped. Once that
     * commits, the caller's write re-checks the locked row, finds round 1 set and writes nothing: skipped and the token
     * stay. The caller's statement snapshot still saw the run admitted; only the row it writes decides.
     */
    @Test
    void aBlockedHeaderWriteOfTheSameRoundNeverResetsTheReadiedRun() throws Exception {
        CompletableFuture<Void> callersWrite;
        try (Connection recovery = dataSource.getConnection()) {
            recovery.setAutoCommit(false);
            try (PreparedStatement readied = recovery.prepareStatement("UPDATE review_status SET status = 'skipped',"
                    + " run_token = 'TEST-readied', run_round = 1 WHERE review_id = ?")) {
                readied.setString(1, reviewId);
                readied.executeUpdate();
            }
            callersWrite = CompletableFuture.runAsync(this::registerRunHeader);
            assertTrue(waitsOnALock("%ON CONFLICT%"), "the caller's header write waits for the row");
            recovery.commit();
            recovery.setAutoCommit(true);
        }
        callersWrite.get(30, TimeUnit.SECONDS);
        assertEquals("skipped:TEST-readied:1", header());
    }

    private void registerRunHeader() {
        projection.registerRunHeader(new ReviewProjection.RunHeader(reviewId, 1, new RepoRef(workspace, "TEST-repo"), 7L,
                "TEST-title", "TEST-author", "TEST-1", "TEST-feature", "main", HEAD, "https://forge.example.test/TEST/7",
                "github"));
    }

    /** Whether a readying statement is blocked on a row lock, polled for up to 10 s. */
    private boolean waitsOnALock() throws Exception {
        return waitsOnALock("%FOR UPDATE%review_checks_wait%");
    }

    /** Whether a statement whose text matches {@code pattern} is blocked on a row lock, polled for up to 10 s. */
    private boolean waitsOnALock(String pattern) throws Exception {
        for (int poll = 0; poll < 100; poll++) {
            try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE ?")) {
                query.setString(1, pattern);
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next() && rows.getLong(1) > 0) return true;
                }
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }
        return false;
    }

    /** The header as {@code status:run_token:run_round}. */
    private String header() throws Exception {
        return read("SELECT status || ':' || run_token || ':' || COALESCE(run_round::text, 'null') FROM review_status"
                + " WHERE review_id = ?");
    }

    private String state() throws Exception {
        return read("SELECT state FROM review_checks_wait WHERE review_id = ?");
    }

    private String read(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reviewId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private void execute(String sql, Object... parameters) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            statement.executeUpdate();
        }
    }
}
