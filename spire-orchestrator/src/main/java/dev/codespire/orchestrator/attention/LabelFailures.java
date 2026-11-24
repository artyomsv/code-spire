package dev.codespire.orchestrator.attention;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Writes why a posted review's verdict label call failed (verdict labels, V120), the column
 * {@link LabelFailureAttentionRows} reads. Its own bean rather than another method on {@code ReviewProjection}, which
 * already holds more than a reader can.
 */
@ApplicationScoped
public class LabelFailures {

    @Inject
    DataSource dataSource;

    @Inject
    AttentionBroadcaster attention;

    /**
     * Every CommentsPosted writes it, so a later success or the setting turned off clears the row a failure raised.
     * Guarded by {@code commit_sha}, as {@code recordPosted} is: a superseded run's report cannot speak for the newer
     * head.
     *
     * @param reason the failure, or null to clear it
     */
    public void record(String reviewId, String commit, String reason) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE review_status SET label_failure = ? WHERE review_id = ? AND commit_sha = ?")) {
            ps.setString(1, reason);
            ps.setString(2, reviewId);
            ps.setString(3, commit);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record the verdict label outcome of " + reviewId, e);
        }
        attention.refresh();
    }
}
