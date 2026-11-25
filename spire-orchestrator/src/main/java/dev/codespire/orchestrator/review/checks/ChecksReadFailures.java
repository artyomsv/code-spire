package dev.codespire.orchestrator.review.checks;

import dev.codespire.orchestrator.attention.AttentionBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Why the last read of a waiting run's checks failed (review waits for CI, V121 {@code review_checks_wait.read_failure}):
 * a token without "Checks: read" and "Commit statuses: read", or a forge that refused. A waiting row with one raises
 * {@code REVIEW_CHECKS_UNREADABLE} at once, rather than the review sitting 60 minutes and blaming CI; a review started
 * on the timeout with one says its checks could not be read. Its own bean, beside {@link ChecksWaitStore}, which reads
 * it back ({@link ChecksWaitStore#holdOn}).
 */
@ApplicationScoped
public class ChecksReadFailures {

    @Inject
    DataSource dataSource;

    @Inject
    AttentionBroadcaster attention;

    /** Records the read of a waiting run: its failure, or null after a read that answered; the bell moves on a change. */
    public void record(ChecksWait.Run run, String failure) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE review_checks_wait SET read_failure = ? WHERE review_id = ? AND round = ? AND state = 'waiting'"
                        + " AND read_failure IS DISTINCT FROM ?")) {
            statement.setString(1, failure);
            statement.setString(2, run.reviewId());
            statement.setInt(3, run.round());
            statement.setString(4, failure);
            if (statement.executeUpdate() == 1) attention.refresh();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record the check read of " + run.reviewId(), e);
        }
    }
}
