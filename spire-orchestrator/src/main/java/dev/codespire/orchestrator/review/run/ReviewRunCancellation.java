package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.RunCommandEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Stops a review's harness run when what it reads is gone (harness reviews, step 4): a superseded head, or a closed pull
 * request. It sends {@code CancelRun} on the run-control topic for each live review-kind run, and never
 * {@code CancelReview}: ADR-013 keeps the lifecycle's own decisions with the aggregate.
 *
 * <p>A cancel that arrives before the run starts is held by the worker's cancel claim, so the run never starts; one
 * that arrives while it runs stops the unit. A finished run is not live and is sent nothing.
 *
 * <p>A run still being dispatched has no row to cancel yet, so each cancellation takes the review's admission lock
 * ({@link ReviewRunAdmission}) before it reads the live runs, and a supersede records the replaced head first: a
 * dispatch admitted before the lock is visible here, and one admitted after it is refused (review of PR #367).
 */
@ApplicationScoped
public class ReviewRunCancellation {

    private static final Logger LOG = Logger.getLogger(ReviewRunCancellation.class);

    /** Twelve characters of a head, as the run id spells it. */
    private static final int COMMIT_CHARS = 12;

    private static final String INTENT =
            "INSERT INTO review_run_cancel (review_id, commit_sha) VALUES (?, ?) ON CONFLICT DO NOTHING";

    @Inject
    DataSource dataSource;

    @Inject
    FactoryRunProjection runs;

    @Inject
    RunCommandEmitter emitter;

    /** Cancel every live review run of {@code reviewId} in {@code supersededRound} or earlier: that round has ended. */
    public void supersede(String reviewId, int supersededRound) {
        throw new UnsupportedOperationException("a supersede is not keyed by round yet: " + reviewId);
    }

    /**
     * Cancel every live review run of {@code reviewId}, a reply run on a completed review included: its pull request
     * closed. The caller has already written the closed state, which a later admission reads.
     */
    public void close(String reviewId) {
        cancel(underLock(reviewId, null), "pull request closed");
    }

    /** The live runs to cancel, read under the admission lock after recording a superseded head (when one is given). */
    private List<String> underLock(String reviewId, String supersededCommit) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                ReviewRunAdmission.lock(c, reviewId);
                if (supersededCommit != null) {
                    record(c, reviewId, supersededCommit);
                }
                List<String> live = runs.liveReviewRuns(reviewId, supersededCommit);
                c.commit();
                return live;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException | RuntimeException e) {
            // Not rethrown, for the reason cancel() gives: the close path must still cancel the review. The runs finish
            // and are charged, which is a cost, never a wrong review.
            LOG.errorf(e, "the review runs of %s could not be read for a cancel; any live one runs to its end", reviewId);
            return List.of();
        }
    }

    private static void record(Connection c, String reviewId, String supersededCommit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INTENT)) {
            ps.setString(1, reviewId);
            ps.setString(2, supersededCommit);
            ps.executeUpdate();
        }
    }

    private void cancel(List<String> runIds, String reason) {
        for (String runId : runIds) {
            try {
                emitter.control(new RunCommand.CancelRun(runId, reason));
            } catch (RuntimeException e) {
                // Not rethrown: the caller is the close path, and a throw would dead-letter the event that also
                // cancels the review. The run finishes and is charged, which is a cost, never a wrong review.
                LOG.errorf(e, "review run %s could not be cancelled (%s); it runs to its end", runId, reason);
            }
        }
    }
}
