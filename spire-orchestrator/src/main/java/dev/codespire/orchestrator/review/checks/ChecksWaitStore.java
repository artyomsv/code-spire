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
 * The durable admission state of review runs and the waits of "Start review: after checks finish"
 * ({@code review_checks_wait}, V121): one row per review, its latest admitted run. A wait lives in the database, so a
 * restart resumes it. Moving a waiting row to a claimed stage ({@link #claim}) is the permit to act on
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
    private static final String DUE_STAGES = "('admitted', 'waiting', 'starting', 'starting_unfinished', 'skipping')";

    @Inject
    DataSource dataSource;

    /** Records a waiting run; see {@link #register}. */
    public void startWaiting(ChecksWait wait) {
        register(wait.at(Stage.WAITING));
    }

    /**
     * Records an admitted run in its stage, replacing the row of an EARLIER run (a new head or a re-run supersedes it).
     * A row of the same or a later run is kept, whatever its stage, so a late caller never replaces a newer run.
     */
    public void register(ChecksWait wait) {
        update("""
                INSERT INTO review_checks_wait (review_id, repository_id, head_commit, round, untrusted, state,
                                                waiting_since, next_read_at, failed_checks)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)
                ON CONFLICT (review_id) DO UPDATE SET repository_id = EXCLUDED.repository_id,
                       head_commit = EXCLUDED.head_commit, round = EXCLUDED.round, untrusted = EXCLUDED.untrusted,
                       state = EXCLUDED.state, waiting_since = EXCLUDED.waiting_since, next_read_at = EXCLUDED.next_read_at,
                       failed_checks = NULL
                 WHERE review_checks_wait.round < EXCLUDED.round
                """, statement -> {
            statement.setString(1, wait.reviewId());
            statement.setObject(2, wait.repositoryId());
            statement.setString(3, wait.head());
            statement.setInt(4, wait.round());
            statement.setBoolean(5, wait.untrusted());
            statement.setString(6, wait.stage().stored());
            statement.setTimestamp(7, Timestamp.from(wait.waitingSince()));
            statement.setTimestamp(8, Timestamp.from(wait.waitingSince().plus(ChecksWaitDecision.GRACE)));
        });
    }

    /**
     * Makes an admitted run readable once its review header is set up: before this no wake-up reads it and nothing can
     * claim it, so a start or a skip never lands before the header write that would undo its status and token.
     */
    public boolean ready(String reviewId, int round) {
        return update("UPDATE review_checks_wait SET state = 'waiting'" + ROUND + " AND state = 'admitted'", statement -> {
            statement.setString(1, reviewId);
            statement.setInt(2, round);
        }) == 1;
    }

    /** The round of the review's latest admitted run, 0 when none was recorded. */
    public int admittedRound(String reviewId) {
        return roundOf("SELECT round FROM review_checks_wait WHERE review_id = ?", reviewId);
    }

    /**
     * Shows the review as waiting for checks only while {@code round} still waits, in one statement, so a late caller
     * never overwrites the status a start or a skip of that run, or a later run, has written since.
     */
    public boolean markWaiting(String reviewId, int round) {
        return update("UPDATE review_status SET status = ?, updated_at = now() WHERE review_id = ? AND EXISTS (SELECT 1"
                + " FROM review_checks_wait w WHERE w.review_id = ? AND w.round = ? AND w.state = 'waiting')", statement -> {
            statement.setString(1, ChecksWaitStatus.WAITING);
            statement.setString(2, reviewId);
            statement.setString(3, reviewId);
            statement.setInt(4, round);
        }) == 1;
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

    /** Completes a claimed outcome in its terminal stage, which stays as the run's admission record. */
    public void finish(ChecksWait claimed) {
        update("UPDATE review_checks_wait SET state = ?" + ROUND + " AND state = ?", statement -> {
            statement.setString(1, terminal(claimed.stage()).stored());
            statement.setString(2, claimed.reviewId());
            statement.setInt(3, claimed.round());
            statement.setString(4, claimed.stage().stored());
        });
    }

    private static Stage terminal(Stage claimed) {
        return switch (claimed) {
            case STARTING_UNFINISHED -> Stage.TIMED_OUT;
            case SKIPPING -> Stage.SKIPPED;
            default -> Stage.STARTED;
        };
    }

    /**
     * Drops the row as it was observed: this run, in this stage. A reader holding a stale waiting snapshot never drops
     * the claim another resolver made since, which is that claim's recovery intent; a newer run's row is untouched.
     */
    public void drop(ChecksWait observed) {
        update("DELETE FROM review_checks_wait" + ROUND + " AND state = ?", statement -> {
            bindRun(statement, observed);
            statement.setString(3, observed.stage().stored());
        });
    }

    /** Whether this very run (review, head and round) started, or is starting, before its checks finished. */
    public boolean startedUnfinished(ChecksWait.Run run) {
        return exists("SELECT 1 FROM review_checks_wait WHERE review_id = ? AND head_commit = ? AND round = ?"
                + " AND state IN ('starting_unfinished', 'timed_out')", statement -> {
            statement.setString(1, run.reviewId());
            statement.setString(2, run.head());
            statement.setInt(3, run.round());
        });
    }

    /**
     * Sets up the review header for an admitted run whose caller died before it did (V121): the run's head, status
     * "reviewing" and a fresh run token, as the saga's header write would. The caller's own write, if it comes late, is
     * then fenced by ReviewProjection because the run has left 'admitted'.
     */
    public void startHeader(ChecksWait admitted) {
        update("UPDATE review_status SET commit_sha = ?, status = 'reviewing', stage = 1, attempt = 1, error_detail = NULL,"
                + " answering = FALSE, posted_verdict = NULL, posted_review_ref = NULL, posted_review_url = NULL,"
                + " run_token = md5(random()::text || clock_timestamp()::text), updated_at = now() WHERE review_id = ?",
                statement -> {
                    statement.setString(1, admitted.head());
                    statement.setString(2, admitted.reviewId());
                });
    }

    /** Whether the review waits for checks now (admitted or waiting), on any head. */
    public boolean isWaiting(String reviewId) {
        return exists("SELECT 1 FROM review_checks_wait WHERE review_id = ? AND state IN ('admitted', 'waiting')",
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
     * Claims up to {@code limit} rows whose read or recovery is due (waiting or claimed, never terminal), pushing each
     * one's due time {@link ChecksWaitDecision#SWEEP} ahead in the same statement, so overlapping sweeps or replicas
     * never take one row at once. Empty on a database failure: the rows stay due and the next sweep tries again.
     */
    public List<ChecksWait> claimDue(int limit) {
        try {
            return query("UPDATE review_checks_wait SET next_read_at = now() + make_interval(secs => ?)"
                    + " WHERE review_id IN (SELECT review_id FROM review_checks_wait WHERE state IN " + DUE_STAGES
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

    private int roundOf(String sql, String reviewId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reviewId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Check wait read failed", failure);
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
