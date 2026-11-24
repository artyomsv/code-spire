package dev.codespire.orchestrator.review.checks;

import dev.codespire.orchestrator.review.checks.ChecksWait.Stage;
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
 * database, so a restart resumes it. Moving a waiting row to a claimed stage ({@link #claim}) is the permit to act on
 * that run: it is guarded on the stage and the round, so only one resolver ever acts, and the claimed row is the
 * recoverable intent until {@link #finish}. Every change after the claim names the round, so it never touches the wait
 * of a newer head or a re-run that replaced the row.
 */
@ApplicationScoped
public class ChecksWaitStore {

    private static final Logger LOG = Logger.getLogger(ChecksWaitStore.class);
    private static final String COLUMNS =
            "review_id, repository_id, head_commit, round, untrusted, waiting_since, state, failed_checks";
    private static final String ROUND = " WHERE review_id = ? AND round = ?";

    @Inject
    DataSource dataSource;

    /** Records the wait, replacing any earlier wait, claim or hold of the review (a new head or re-run supersedes). */
    public void startWaiting(ChecksWait wait) {
        update("""
                INSERT INTO review_checks_wait (review_id, repository_id, head_commit, round, untrusted, state,
                                                waiting_since, next_read_at, failed_checks)
                VALUES (?, ?, ?, ?, ?, 'waiting', ?, ?, NULL)
                ON CONFLICT (review_id) DO UPDATE SET repository_id = EXCLUDED.repository_id,
                       head_commit = EXCLUDED.head_commit, round = EXCLUDED.round, untrusted = EXCLUDED.untrusted,
                       state = 'waiting', waiting_since = EXCLUDED.waiting_since, next_read_at = EXCLUDED.next_read_at,
                       failed_checks = NULL
                """, statement -> {
            statement.setString(1, wait.reviewId());
            statement.setObject(2, wait.repositoryId());
            statement.setString(3, wait.head());
            statement.setInt(4, wait.round());
            statement.setBoolean(5, wait.untrusted());
            statement.setTimestamp(6, Timestamp.from(wait.waitingSince()));
            statement.setTimestamp(7, Timestamp.from(wait.waitingSince().plus(ChecksWaitDecision.GRACE)));
        });
    }

    /** Drops the review's wait, claim or hold: its review starts at once. */
    public void clear(String reviewId) {
        update("DELETE FROM review_checks_wait WHERE review_id = ?", statement -> statement.setString(1, reviewId));
    }

    /**
     * Claims the waiting run as {@code claimed.stage()}; true for exactly one caller. The sweep finishes the claim if
     * the claimant dies before {@link #finish}.
     */
    public boolean claim(ChecksWait claimed) {
        return update("UPDATE review_checks_wait SET state = ?, failed_checks = ?,"
                + " next_read_at = now() + make_interval(secs => ?)" + ROUND + " AND state = 'waiting'", statement -> {
            statement.setString(1, claimed.stage().stored());
            statement.setString(2, claimed.failedChecks());
            statement.setDouble(3, ChecksWaitDecision.SWEEP.toSeconds());
            statement.setString(4, claimed.reviewId());
            statement.setInt(5, claimed.round());
        }) == 1;
    }

    /** Completes a claimed outcome: the row goes, except a timed-out start's, which stays as the hold. */
    public void finish(ChecksWait claimed) {
        String sql = claimed.stage() == Stage.STARTING_UNFINISHED
                ? "UPDATE review_checks_wait SET state = 'timed_out'" + ROUND + " AND state = ?"
                : "DELETE FROM review_checks_wait" + ROUND + " AND state = ?";
        update(sql, statement -> {
            bindRun(statement, claimed);
            statement.setString(3, claimed.stage().stored());
        });
    }

    /** Drops this run's row, whatever its stage; a newer run's row is untouched. */
    public void drop(ChecksWait wait) {
        update("DELETE FROM review_checks_wait" + ROUND, statement -> bindRun(statement, wait));
    }

    /** Whether the review of {@code commit} started (or is starting) before its checks finished. */
    public boolean startedUnfinished(String reviewId, String commit) {
        return exists("SELECT 1 FROM review_checks_wait WHERE review_id = ? AND head_commit = ?"
                + " AND state IN ('starting_unfinished', 'timed_out')", statement -> {
            statement.setString(1, reviewId);
            statement.setString(2, commit);
        });
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
     * Claims up to {@code limit} rows whose read or recovery is due (every stage but the timed-out hold), pushing each
     * one's due time {@link ChecksWaitDecision#SWEEP} ahead in the same statement, so overlapping sweeps or replicas
     * never take one row at once. Empty on a database failure: the rows stay due and the next sweep tries again.
     */
    public List<ChecksWait> claimDue(int limit) {
        try {
            return query("UPDATE review_checks_wait SET next_read_at = now() + make_interval(secs => ?)"
                    + " WHERE review_id IN (SELECT review_id FROM review_checks_wait WHERE state <> 'timed_out'"
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

    private static void bindRun(PreparedStatement statement, ChecksWait wait) throws SQLException {
        statement.setString(1, wait.reviewId());
        statement.setInt(2, wait.round());
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
                rows.getString("head_commit"), rows.getInt("round"), rows.getBoolean("untrusted"),
                rows.getTimestamp("waiting_since").toInstant(), Stage.ofStored(rows.getString("state")),
                rows.getString("failed_checks"));
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
