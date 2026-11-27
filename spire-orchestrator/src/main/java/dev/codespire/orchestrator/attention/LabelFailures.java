package dev.codespire.orchestrator.attention;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Writes why a review label call failed (verdict labels, V120; a skipped run's {@code review: ci-failed}, V121), the
 * column {@link LabelFailureAttentionRows} reads. Its own bean rather than another method on {@code ReviewProjection},
 * which already holds more than a reader can.
 */
@ApplicationScoped
public class LabelFailures {

    private static final String BY_HEAD = "UPDATE review_status SET label_failure = ? WHERE review_id = ? AND commit_sha = ?";

    @Inject
    DataSource dataSource;

    @Inject
    AttentionBroadcaster attention;

    /** A skipped run whose label outcome is reported: the review, its head and its round. */
    public record Run(String reviewId, String commit, int round) {
    }

    /**
     * Every CommentsPosted writes it, so a later success or the setting turned off clears the row a failure raised.
     * Guarded by {@code commit_sha}, as {@code recordPosted} is: a superseded run's report cannot speak for the newer
     * head.
     *
     * @param reason the failure, or null to clear it
     */
    public void record(String reviewId, String commit, String reason) {
        write(BY_HEAD, reviewId, statement -> {
            statement.setString(1, reason);
            statement.setString(2, reviewId);
            statement.setString(3, commit);
        });
    }

    /**
     * A skipped run's ci-failed label outcome, guarded by its head and by its round ({@code run_round}): a re-run on
     * the same head writes its own header, so a late report of the skipped run never clears or raises the re-run's row.
     *
     * @param reason the failure, or null to clear it
     */
    public void recordForRun(Run run, String reason) {
        write(BY_HEAD + " AND COALESCE(run_round, 0) = ?", run.reviewId(), statement -> {
            statement.setString(1, reason);
            statement.setString(2, run.reviewId());
            statement.setString(3, run.commit());
            statement.setInt(4, run.round());
        });
    }

    private void write(String sql, String reviewId, Binder binder) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record the review label outcome of " + reviewId, e);
        }
        attention.refresh();
    }

    /** Binds one write's parameters. */
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
