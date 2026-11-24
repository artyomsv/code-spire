package dev.codespire.orchestrator.review.checks;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The durable waits of "Start review: after checks finish" ({@code review_checks_wait}, V121). A wait lives in the
 * database, so an orchestrator restart resumes it. Taking a waiting row ({@link #take}, {@link #markTimedOut}) is the
 * permit to start or skip its review: only one caller can take a row, so a duplicate wake-up starts one review.
 */
@ApplicationScoped
public class ChecksWaitStore {

    private static final Logger LOG = Logger.getLogger(ChecksWaitStore.class);
    private static final String COLUMNS = "review_id, repository_id, head_commit, untrusted, waiting_since";

    @Inject
    DataSource dataSource;

    /** Records the wait for {@code wait.head()}, replacing any earlier wait or hold of the review (a new head supersedes). */
    public void startWaiting(ChecksWait wait) {
        update("""
                INSERT INTO review_checks_wait (review_id, repository_id, head_commit, untrusted, state, waiting_since,
                                                next_read_at)
                VALUES (?, ?, ?, ?, 'waiting', ?, ?)
                ON CONFLICT (review_id) DO UPDATE SET repository_id = EXCLUDED.repository_id,
                       head_commit = EXCLUDED.head_commit, untrusted = EXCLUDED.untrusted, state = 'waiting',
                       waiting_since = EXCLUDED.waiting_since, next_read_at = EXCLUDED.next_read_at
                """, statement -> {
            statement.setString(1, wait.reviewId());
            statement.setObject(2, wait.repositoryId());
            statement.setString(3, wait.head());
            statement.setBoolean(4, wait.untrusted());
            statement.setTimestamp(5, Timestamp.from(wait.waitingSince()));
            statement.setTimestamp(6, Timestamp.from(wait.waitingSince().plus(ChecksWaitDecision.GRACE)));
        });
    }

    /** Drops the review's wait or hold: its review starts at once. */
    public void clear(String reviewId) {
        update("DELETE FROM review_checks_wait WHERE review_id = ?", statement -> statement.setString(1, reviewId));
    }

    /** Takes the review's wait on {@code head}; true for exactly one caller, the one that may start or skip it. */
    public boolean take(String reviewId, String head) {
        return update("DELETE FROM review_checks_wait WHERE review_id = ? AND head_commit = ? AND state = 'waiting'",
                statement -> bindReview(statement, reviewId, head)) == 1;
    }

    /** Takes the wait as a timeout: the row stays as the hold that keeps the started review from approving. */
    public boolean markTimedOut(String reviewId, String head) {
        return update("UPDATE review_checks_wait SET state = 'timed_out' WHERE review_id = ? AND head_commit = ?"
                + " AND state = 'waiting'", statement -> bindReview(statement, reviewId, head)) == 1;
    }

    /** Whether the review of {@code commit} started before its checks finished, so it must not approve. */
    public boolean startedUnfinished(String reviewId, String commit) {
        return exists("SELECT 1 FROM review_checks_wait WHERE review_id = ? AND head_commit = ? AND state = 'timed_out'",
                statement -> bindReview(statement, reviewId, commit));
    }

    /** Whether the review waits for checks now, on any head. */
    public boolean isWaiting(String reviewId) {
        return exists("SELECT 1 FROM review_checks_wait WHERE review_id = ? AND state = 'waiting'",
                statement -> statement.setString(1, reviewId));
    }

    /** The waits on {@code head} in the repository: what a webhook wake-up reads. */
    public List<ChecksWait> waitingOn(UUID repositoryId, String head) {
        return query("SELECT " + COLUMNS + " FROM review_checks_wait WHERE repository_id = ? AND head_commit = ?"
                + " AND state = 'waiting'", statement -> {
            statement.setObject(1, repositoryId);
            statement.setString(2, head);
        });
    }

    /**
     * Claims up to {@code limit} waits whose read is due, pushing each one's next read {@link ChecksWaitDecision#SWEEP}
     * ahead in the same statement, so overlapping sweeps or replicas never read one wait twice. Empty on a database
     * failure: the rows stay due and the next sweep tries again.
     */
    public List<ChecksWait> claimDue(int limit) {
        try {
            return query("UPDATE review_checks_wait SET next_read_at = now() + make_interval(secs => ?)"
                    + " WHERE review_id IN (SELECT review_id FROM review_checks_wait WHERE state = 'waiting'"
                    + " AND next_read_at <= now() ORDER BY next_read_at LIMIT ? FOR UPDATE SKIP LOCKED)"
                    + " RETURNING " + COLUMNS, statement -> {
                statement.setDouble(1, ChecksWaitDecision.SWEEP.toSeconds());
                statement.setInt(2, limit);
            });
        } catch (IllegalStateException failure) {
            LOG.warnf(failure, "Could not claim the due check waits; the next sweep tries again");
            return List.of();
        }
    }

    private static void bindReview(PreparedStatement statement, String reviewId, String head) throws SQLException {
        statement.setString(1, reviewId);
        statement.setString(2, head);
    }

    private int update(String sql, Binder binder) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("Check wait write failed", failure);
        }
    }

    private boolean exists(String sql, Binder binder) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Check wait read failed", failure);
        }
    }

    private List<ChecksWait> query(String sql, Binder binder) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            List<ChecksWait> waits = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) waits.add(waitOf(rows));
            }
            return waits;
        } catch (SQLException failure) {
            throw new IllegalStateException("Check wait read failed", failure);
        }
    }

    private static ChecksWait waitOf(ResultSet rows) throws SQLException {
        return new ChecksWait(rows.getString("review_id"), rows.getObject("repository_id", UUID.class),
                rows.getString("head_commit"), rows.getBoolean("untrusted"),
                rows.getTimestamp("waiting_since").toInstant());
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
