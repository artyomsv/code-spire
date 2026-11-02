package dev.codespire.orchestrator.review.run;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * One lock per review that serialises a forced re-run with a harness outcome (review of PR #368, code-quality/H-1).
 *
 * <p>A forced re-run on the same head appends the next round's {@code ReviewRequested} and replaces the review's run
 * token while holding it; a harness outcome reads the current round and takes every effect of its answer while holding
 * it. So an outcome either sees the re-run's round and is dropped, or finishes before the re-run starts: round 1's
 * answer is never written into round 2 or posted with round 2's token. The commit checks on the outcome path cannot
 * tell those two rounds apart, because a forced re-run keeps the commit.
 *
 * <p>A Postgres advisory lock held by a transaction on its own connection, as {@link ReviewRunAdmission}'s is, so it ends
 * with the transaction on commit, rollback or a lost connection. The work runs on other connections and must never take
 * this lock itself: it would wait for its own caller for ever. Its key is not admission's on purpose: an outcome or a
 * re-run that dispatched or cancelled a review run inside the lock would otherwise deadlock on itself, and nothing here
 * needs ordering against admission.
 */
@ApplicationScoped
public class ReviewRoundLock {

    private static final String KEY_PREFIX = "review-round:";

    @Inject
    DataSource dataSource;

    /** Runs {@code work} while holding {@code reviewId}'s round lock, and returns what it returned. */
    public <T> T holding(String reviewId, Supplier<T> work) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                lock(c, reviewId);
                T done = work.get();
                c.commit();
                return done;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                // Back to the pool as it came: the next borrower expects autocommit (ReviewProjection.recordCharges).
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The round lock of review " + reviewId + " could not be taken or released;"
                    + " check the database connection, then retry the re-run or replay the dead-lettered result", e);
        }
    }

    private static void lock(Connection c, String reviewId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            ps.setString(1, KEY_PREFIX + reviewId);
            ps.execute();
        }
    }
}
