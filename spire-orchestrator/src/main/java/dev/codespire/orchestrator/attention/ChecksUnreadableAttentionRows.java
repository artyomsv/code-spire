package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * A review waiting for its checks whose last read of them failed (review waits for CI, V121): usually the reviewer
 * account's token lacks "Checks: read" or "Commit statuses: read". Raised on the first failed read, so the operator is
 * not left with a review that waits 60 minutes and then never approves. Not dismissable: the next read that answers,
 * or the wait ending, clears it.
 */
final class ChecksUnreadableAttentionRows {

    static final String CODE = "REVIEW_CHECKS_UNREADABLE";

    private ChecksUnreadableAttentionRows() {
    }

    static void collect(Connection connection, List<AttentionView> rows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.workspace, s.slug, s.pr_id, w.read_failure, s.updated_at
                  FROM review_checks_wait w JOIN review_status s ON s.review_id = w.review_id
                 WHERE w.read_failure IS NOT NULL AND w.state = 'waiting' AND s.archived_at IS NULL
                 ORDER BY s.updated_at DESC
                """); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                String workspace = results.getString("workspace");
                String slug = results.getString("slug");
                long pr = results.getLong("pr_id");
                rows.add(new AttentionView(CODE, AttentionView.Severity.WARNING, workspace + "/" + slug + "#" + pr,
                        "Checks on " + workspace + "/" + slug + " could not be read (" + results.getString("read_failure")
                                + "): the review waits and will not approve. Give the reviewer account Checks: read and"
                                + " Commit statuses: read.", "/r/" + workspace + "/" + slug + "/" + pr)
                        .withSince(StoredTime.of(results, "updated_at")));
            }
        }
    }
}
