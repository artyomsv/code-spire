package dev.codespire.orchestrator.review.checks;

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
 * Readying a waiting run is fenced on the review header row it writes (V121, {@code review_status.run_round}), against
 * the real schema: a header a later round wrote, committed before or while the readying runs, is never replaced. A
 * newer run started at once records no wait row, so the header row is the only state both writes share.
 */
@QuarkusTest
class ChecksWaitReadyFenceTest {

    private static final String HEAD = "a".repeat(40);

    @Inject ChecksWaitStore store;
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

    /** Whether a readying statement is blocked on a row lock, polled for up to 10 s. */
    private boolean waitsOnALock() throws Exception {
        for (int poll = 0; poll < 100; poll++) {
            try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                            + " AND query LIKE '%FOR UPDATE%review_checks_wait%'");
                 ResultSet rows = query.executeQuery()) {
                if (rows.next() && rows.getLong(1) > 0) return true;
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
