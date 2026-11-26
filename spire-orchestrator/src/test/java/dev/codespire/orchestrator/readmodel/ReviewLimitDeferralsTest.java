package dev.codespire.orchestrator.readmodel;

import dev.codespire.orchestrator.readmodel.ReviewLimitDeferrals.Deferral;
import dev.codespire.orchestrator.readmodel.ReviewLimitDeferrals.Outcome;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The deferral's SQL against the real schema (V122, PR 456 code-quality/M-4): a failed read of the Review diff limit
 * moves {@code retry_at} to at most two minutes ahead, never moves the attempt, keeps an earlier due time, and closes
 * after sixty minutes on one head. Sequential calls: these prove the SQL predicates, not the row lock.
 */
@QuarkusTest
class ReviewLimitDeferralsTest {

    private static final String HEAD = "a".repeat(40);
    private static final String NEXT_HEAD = "b".repeat(40);
    private static final Instant START = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Inject ReviewLimitDeferrals deferrals;
    @Inject ReviewProjection projection;
    @Inject DataSource dataSource;

    private final String workspace = "TEST-" + UUID.randomUUID();
    private final String reviewId = "github:" + workspace + "/TEST-repo#7";

    @BeforeEach
    void seedReview() throws SQLException {
        execute("INSERT INTO review_status (review_id, workspace, slug, pr_id, status) VALUES (?, ?, 'TEST-repo', 7,"
                + " 'reviewing')", reviewId, workspace);
    }

    @AfterEach
    void removeReview() throws SQLException {
        execute("DELETE FROM review_status WHERE workspace = ?", workspace);
    }

    @Test
    void duplicatesBeforeAndAfterTheSweeperFiresNeverMoveTheAttempt() throws SQLException {
        int attempt = projection.currentAttempt(reviewId);

        assertEquals(Outcome.DEFERRED, defer(HEAD, Duration.ZERO));
        assertEquals(Outcome.DEFERRED, defer(HEAD, Duration.ofSeconds(30)), "a duplicate before the sweep");
        assertEquals(START.plus(ReviewLimitDeferrals.DELAY), retryAt(), "the duplicate keeps the first due time");
        assertEquals(attempt, projection.currentAttempt(reviewId));

        execute("UPDATE review_status SET retry_at = NULL WHERE review_id = ?", reviewId); // as claimDueRetries does
        assertEquals(Outcome.DEFERRED, defer(HEAD, Duration.ofMinutes(3)), "a stale duplicate after the sweep");
        assertEquals(START.plus(Duration.ofMinutes(3)).plus(ReviewLimitDeferrals.DELAY), retryAt(),
                "one pending due time, the stale delivery's");
        assertEquals(attempt, projection.currentAttempt(reviewId), "no delivery spends a retry attempt");
    }

    @Test
    void anEarlierDueRetryIsKept() throws SQLException {
        Instant due = START.plusSeconds(10);
        execute("UPDATE review_status SET retry_at = ? WHERE review_id = ?", Timestamp.from(due), reviewId);

        assertEquals(Outcome.DEFERRED, defer(HEAD, Duration.ZERO));

        assertEquals(due, retryAt());
    }

    @Test
    void aFailedReadWithinTheWindowStillDefers() throws SQLException {
        defer(HEAD, Duration.ZERO);

        assertEquals(Outcome.DEFERRED, defer(HEAD, Duration.ofMinutes(59)));
    }

    @Test
    void aFailedReadPastTheWindowExpiresAndClosesIt() throws SQLException {
        defer(HEAD, Duration.ZERO);
        execute("UPDATE review_status SET retry_at = NULL WHERE review_id = ?", reviewId);

        assertEquals(Outcome.EXPIRED, defer(HEAD, Duration.ofMinutes(61)));

        assertNull(retryAt(), "an expired read schedules nothing");
        assertFalse(hasWindow(), "the window is closed, so a re-run starts a new one");
    }

    @Test
    void aSuccessfulReadClosesTheWindow() throws SQLException {
        defer(HEAD, Duration.ZERO);

        deferrals.clear(reviewId);

        assertFalse(hasWindow());
        assertEquals(Outcome.DEFERRED, defer(HEAD, Duration.ofMinutes(61)), "a later failure starts a new window");
    }

    @Test
    void anotherHeadStartsANewWindow() throws SQLException {
        defer(HEAD, Duration.ZERO);

        assertEquals(Outcome.DEFERRED, defer(NEXT_HEAD, Duration.ofMinutes(61)));
    }

    @Test
    void aReviewNoLongerRunningIsLeftAlone() throws SQLException {
        execute("UPDATE review_status SET status = 'failed' WHERE review_id = ?", reviewId);

        assertEquals(Outcome.NOT_RUNNING, defer(HEAD, Duration.ZERO));

        assertNull(retryAt(), "a stale delivery does not resurrect a finished review");
        assertFalse(hasWindow());
    }

    @Test
    void anArchivedReviewIsLeftAlone() throws SQLException {
        execute("UPDATE review_status SET archived_at = now() WHERE review_id = ?", reviewId);

        assertEquals(Outcome.NOT_RUNNING, defer(HEAD, Duration.ZERO));
        assertNull(retryAt());
    }

    private Outcome defer(String head, Duration after) {
        return deferrals.defer(new Deferral(reviewId, head, START.plus(after)));
    }

    private Instant retryAt() throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT retry_at FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Timestamp at = rs.getTimestamp(1);
                return at == null ? null : at.toInstant();
            }
        }
    }

    private boolean hasWindow() throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM review_limit_deferral WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                ps.setObject(i + 1, parameters[i]);
            }
            ps.executeUpdate();
        }
    }
}
