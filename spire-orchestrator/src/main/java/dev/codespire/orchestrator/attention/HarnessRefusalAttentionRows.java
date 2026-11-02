package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * A review refused on its repository's harness (harness reviews, step 5): a spent pool or a fork must never become an
 * unnoticed stall, and nothing falls back to the API to hide it.
 *
 * <p>Its own query rather than one of {@code AttentionQueries}'s per-review rows, whose message is fixed per code:
 * each row here carries its own reason. Not dismissable: re-running the review once the cause is fixed clears the
 * status, and archiving it is the operator's other resolution, as for every review row.
 */
final class HarnessRefusalAttentionRows {

    static final String CODE = "REVIEW_HARNESS_REFUSED";

    private HarnessRefusalAttentionRows() {
    }

    static void collect(Connection connection, List<AttentionView> rows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT workspace, slug, pr_id, harness_refusal FROM review_status
                 WHERE lower(status) = 'refused'
                   AND harness_refusal IS NOT NULL
                   AND archived_at IS NULL
                 ORDER BY updated_at DESC
                """); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                String workspace = results.getString("workspace");
                String slug = results.getString("slug");
                long pr = results.getLong("pr_id");
                rows.add(new AttentionView(CODE, AttentionView.Severity.WARNING, workspace + "/" + slug + "#" + pr,
                        "Harness review refused: " + results.getString("harness_refusal"),
                        "/r/" + workspace + "/" + slug + "/" + pr));
            }
        }
    }
}
