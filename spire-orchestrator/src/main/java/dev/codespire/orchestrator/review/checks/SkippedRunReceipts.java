package dev.codespire.orchestrator.review.checks;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * The worker's once-per-run receipt of a skipped run's {@code review: ci-failed} label ({@code CI_FAILED:<round>} in
 * {@code worker.comment_idempotency}), opened and closed from here so the label can be fenced by run, not only by head.
 *
 * <p>{@link #open} creates the receipt before {@code LabelChecksFailed} is sent, unposted, so the worker may label.
 * {@link #supersede} runs when the next run of the review is admitted, a re-run of the same head included: every
 * receipt still open is marked {@code superseded}, so the earlier run's command, delayed or replayed, labels nothing.
 * A re-run keeps these receipts when it clears the worker's other claims (ReviewProjection.deleteWorkerClaims).
 *
 * <p>A database with no worker schema (an orchestrator run without a worker) has nothing to open or close.
 */
@ApplicationScoped
public class SkippedRunReceipts {

    static final String PREFIX = "CI_FAILED:";
    static final String SUPERSEDED = "superseded";
    private static final String UNDEFINED_TABLE = "42P01";
    private static final Logger LOG = Logger.getLogger(SkippedRunReceipts.class);

    @Inject
    DataSource dataSource;

    /** Opens the receipt of {@code wait}'s run, once; an existing one (posted or superseded) is kept. */
    public void open(ChecksWait wait) {
        write("INSERT INTO worker.comment_idempotency (review_id, commit, anchor_key) VALUES (?, ?, ?)"
                + " ON CONFLICT (review_id, commit, anchor_key) DO NOTHING", statement -> {
            statement.setString(1, wait.reviewId());
            statement.setString(2, wait.head());
            statement.setString(3, PREFIX + wait.round());
        });
    }

    /** Closes every receipt of the review that no label has used yet: a newer run was admitted. */
    public void supersede(String reviewId) {
        write("UPDATE worker.comment_idempotency SET posted_ref = '" + SUPERSEDED + "', posted_at = now()"
                + " WHERE review_id = ? AND anchor_key LIKE 'CI\\_FAILED:%' AND posted_ref IS NULL",
                statement -> statement.setString(1, reviewId));
    }

    private void write(String sql, Binder binder) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException failure) {
            if (UNDEFINED_TABLE.equals(failure.getSQLState())) {
                LOG.debugf("No worker schema here, so there is no ci-failed receipt to write");
                return;
            }
            throw new IllegalStateException("The ci-failed label receipt could not be written", failure);
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
