package dev.codespire.orchestrator.review.labels;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The desired verdict label of each review's current run (verdict labels, V120; review waits for CI, V121), in the
 * worker's {@code review_label_desired}: the state every label command makes the pull request converge to.
 *
 * <p>A newer run's admission records its round with no label; the run's decided verdict, or its skip for failed
 * checks, records its label. A write never moves the round backwards ({@code round <= EXCLUDED.round}), so an earlier
 * run's late write changes nothing; the worker's CommentIdempotencyStoreTest runs this upsert on the real schema. A
 * database with no worker schema (an orchestrator run without a worker) has nothing to record.
 */
@ApplicationScoped
public class DesiredLabels {

    private static final String TABLE = "worker.review_label_desired";
    private static final Logger LOG = Logger.getLogger(DesiredLabels.class);

    @Inject
    DataSource dataSource;

    /** Records {@code desired} for its review unless a later run already recorded one. */
    public void record(DesiredLabel desired) {
        try (Connection connection = dataSource.getConnection()) {
            if (!workerTableExists(connection)) {
                LOG.debugf("No worker schema here, so no desired verdict label is recorded");
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + TABLE
                    + " (review_id, round, label) VALUES (?, ?, ?) ON CONFLICT (review_id) DO UPDATE SET"
                    + " round = EXCLUDED.round, label = EXCLUDED.label, updated_at = now()"
                    + " WHERE review_label_desired.round <= EXCLUDED.round")) {
                statement.setString(1, desired.reviewId());
                statement.setInt(2, desired.round());
                statement.setString(3, desired.label());
                statement.executeUpdate();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("The desired verdict label of " + desired.reviewId() + " could not be recorded",
                    failure);
        }
    }

    private static boolean workerTableExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, TABLE);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    /** A run's desired label; {@code label} null means no bot verdict label at all. */
    public record DesiredLabel(String reviewId, int round, String label) {
    }
}
