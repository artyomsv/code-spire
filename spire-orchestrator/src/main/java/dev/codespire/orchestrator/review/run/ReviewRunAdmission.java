package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Admits a harness review run's queued row, or refuses it because its review was cancelled first (review of PR #367).
 *
 * <p>Admission and cancellation take one advisory lock per review. A dispatch that holds it first commits its row before
 * a cancellation reads the live runs, so the cancellation sees it; one that takes it after a cancellation sees what the
 * cancellation left: a closed pull request ({@code review_status.pr_state}) or an ended round ({@code
 * review_run_superseded}). The check and the row commit together, and the command is sent after the commit.
 *
 * <p>Rounds, not commits: a forced re-run and a return to an earlier head are later rounds on a head seen before, and
 * are admitted; a call of the ended round, or an earlier one, is refused whenever it arrives. A reply carries the round
 * it was decided in, the current one, so an older supersede never refuses it.
 */
@ApplicationScoped
public class ReviewRunAdmission {

    /** What became of the row. */
    public enum Admission { ADMITTED, ALREADY_QUEUED, CANCELLED }

    private static final String CANCELLED = """
            SELECT EXISTS (SELECT 1 FROM review_status WHERE review_id = ? AND pr_state <> 'OPEN')
                OR EXISTS (SELECT 1 FROM review_run_superseded WHERE review_id = ? AND superseded_round >= ?)
            """;

    @Inject
    DataSource dataSource;

    @Inject
    FactoryRunProjection runs;

    public Admission admit(FactoryRunProjection.QueuedRun row, UUID repositoryId) {
        ReviewRunBinding binding = row.review();
        boolean admitted;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                lock(c, binding.reviewId());
                if (cancelled(c, binding)) {
                    c.rollback();
                    return Admission.CANCELLED;
                }
                admitted = runs.queued(new FactoryRunProjection.QueuedIn(c, row, null, repositoryId));
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                // Back to the pool as it came: the next borrower expects autocommit (ReviewProjection.recordCharges).
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Review run " + row.runId() + " could not be admitted", e);
        }
        if (!admitted) return Admission.ALREADY_QUEUED;
        runs.queuedCommitted(row.runId());
        return Admission.ADMITTED;
    }

    /** The lock admission and cancellation share, held until the caller's transaction ends. */
    static void lock(Connection c, String reviewId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            ps.setString(1, "review-run:" + reviewId);
            ps.execute();
        }
    }

    private static boolean cancelled(Connection c, ReviewRunBinding binding) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(CANCELLED)) {
            ps.setString(1, binding.reviewId());
            ps.setString(2, binding.reviewId());
            ps.setInt(3, binding.round());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }
}
