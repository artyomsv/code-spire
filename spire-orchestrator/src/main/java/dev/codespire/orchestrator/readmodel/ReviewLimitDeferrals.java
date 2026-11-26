package dev.codespire.orchestrator.readmodel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * A review deferred because its Review diff limit setting could not be read (V122, PR 456). A deferral moves
 * {@code review_status.retry_at} to at most {@link #DELAY} ahead and never touches the attempt counter, so the retry
 * sweeper re-runs the pipeline without spending the retry budget, and a redelivered failure can only write the same or
 * an earlier due time. It is bounded by time: a failed read more than {@link #WINDOW} after the first one on the same
 * head is {@link Outcome#EXPIRED}, and the caller fails the review.
 */
@ApplicationScoped
public class ReviewLimitDeferrals {

    /** How long a deferred review waits before the sweeper re-runs it. */
    public static final Duration DELAY = Duration.ofMinutes(2);

    /** How long the setting may stay unreadable for one head before the review fails. */
    public static final Duration WINDOW = Duration.ofMinutes(60);

    static final String NOTE = "Could not read the Review diff limit setting — retrying in 2m.";

    private static final Logger LOG = Logger.getLogger(ReviewLimitDeferrals.class);

    /** What one failed read did to the review. */
    public enum Outcome {
        /** The review waits for the sweeper; its due time is now at most {@link #DELAY} ahead. */
        DEFERRED,
        /** The setting has been unreadable for longer than {@link #WINDOW}; the window is closed. */
        EXPIRED,
        /** The review is no longer running (finished, failed, refused or archived): nothing was written. */
        NOT_RUNNING
    }

    /** One failed read: the review, the head it was reviewing, and when the read failed. */
    public record Deferral(String reviewId, String commit, Instant at) {
    }

    @Inject
    DataSource dataSource;

    /** Record a failed read, in one transaction that locks the review's row, so concurrent deliveries queue. */
    public Outcome defer(Deferral deferral) {
        try (Connection c = dataSource.getConnection()) {
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                Outcome outcome = decide(c, deferral);
                c.commit();
                return outcome;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("review_limit_deferral write failed", e);
        }
    }

    /**
     * The setting was read: close the review's window, so a later failure starts a new one. Best effort, because a
     * successful read must not be blocked by this bookkeeping; a row left behind belongs to this head only.
     */
    public void clear(String reviewId) {
        try (Connection c = dataSource.getConnection()) {
            delete(c, reviewId);
        } catch (SQLException e) {
            LOG.warnf(e, "Could not close the Review diff limit deferral of %s", reviewId);
        }
    }

    private static Outcome decide(Connection c, Deferral deferral) throws SQLException {
        if (!lockRunning(c, deferral.reviewId())) {
            return Outcome.NOT_RUNNING;
        }
        Instant since = openWindow(c, deferral);
        if (since.isBefore(deferral.at().minus(WINDOW))) {
            delete(c, deferral.reviewId());
            return Outcome.EXPIRED;
        }
        postpone(c, deferral);
        return Outcome.DEFERRED;
    }

    private static boolean lockRunning(Connection c, String reviewId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM review_status
                 WHERE review_id = ? AND lower(status) = 'reviewing' AND archived_at IS NULL
                   FOR UPDATE
                """)) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** The window's start: kept for the same head, restarted for another. */
    private static Instant openWindow(Connection c, Deferral deferral) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO review_limit_deferral (review_id, commit_sha, deferred_since) VALUES (?, ?, ?)
                ON CONFLICT (review_id) DO UPDATE
                   SET deferred_since = CASE WHEN review_limit_deferral.commit_sha = EXCLUDED.commit_sha
                                             THEN review_limit_deferral.deferred_since
                                             ELSE EXCLUDED.deferred_since END,
                       commit_sha = EXCLUDED.commit_sha
                RETURNING deferred_since
                """)) {
            ps.setString(1, deferral.reviewId());
            ps.setString(2, deferral.commit());
            ps.setTimestamp(3, Timestamp.from(deferral.at()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        }
    }

    /** LEAST ignores a NULL, so an empty due time is set and an earlier one is kept. */
    private static void postpone(Connection c, Deferral deferral) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE review_status SET retry_at = LEAST(retry_at, ?), note = ?, updated_at = now()
                 WHERE review_id = ?
                """)) {
            ps.setTimestamp(1, Timestamp.from(deferral.at().plus(DELAY)));
            ps.setString(2, NOTE);
            ps.setString(3, deferral.reviewId());
            ps.executeUpdate();
        }
    }

    private static void delete(Connection c, String reviewId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM review_limit_deferral WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            ps.executeUpdate();
        }
    }
}
