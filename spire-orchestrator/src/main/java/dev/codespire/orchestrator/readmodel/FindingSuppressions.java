package dev.codespire.orchestrator.readmodel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Recording which findings a learned preference hid (P4 / FR-10).
 *
 * <p>The write side of the property that makes a counted filter defensible: the rows stay in the
 * corpus rather than being dropped, so a preference that starts hiding findings the team would have
 * acted on can be counted, and revoking it restores them on the next review.
 */
final class FindingSuppressions {

    /**
     * One row per suppressed finding — the same shape the sibling updates use.
     *
     * <p>A bare {@code WHERE} on the location stamps EVERY row there, and two findings on one line is
     * ordinary model output: the visible one would then be recorded as hidden, counted in the
     * suppression total shown on the pull request, and dropped from the corpus the proposal engine
     * reads. {@code suppressed_by IS NULL} also makes a redelivery idempotent rather than letting a
     * second pass walk onto a neighbouring row.
     */
    private static final String MARK = """
            UPDATE review_finding SET suppressed_by = ?
             WHERE id = (SELECT id FROM review_finding
                          WHERE review_id = ? AND round = ? AND path = ? AND start_line = ?
                            AND suppressed_by IS NULL
                          ORDER BY id DESC LIMIT 1)
            """;

    private FindingSuppressions() {
    }

    static void mark(Connection c, String reviewId, int round,
                     SuppressionBatch batch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(MARK)) {
            for (SuppressionBatch.Location location : batch.hidden()) {
                ps.setLong(1, batch.preferenceId());
                ps.setString(2, reviewId);
                ps.setInt(3, round);
                ps.setString(4, location.path());
                ps.setInt(5, location.line());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
