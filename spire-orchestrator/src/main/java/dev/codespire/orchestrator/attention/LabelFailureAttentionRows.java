package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * A posted review whose verdict label could not be set (verdict labels, V120). Labelling is best effort, so the review
 * stands; this row is how the operator learns that the pull request's {@code review: } label does not say who acts
 * next.
 *
 * <p>Its own query, like {@link HarnessRefusalAttentionRows}: each row carries the forge's reason. Not dismissable: the
 * next report of the review writes the column again, so a later success, or the setting turned off, clears it.
 * Archiving the review is the operator's other resolution.
 */
final class LabelFailureAttentionRows {

    static final String CODE = "REVIEW_LABEL_FAILED";

    private LabelFailureAttentionRows() {
    }

    static void collect(Connection connection, List<AttentionView> rows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT workspace, slug, pr_id, label_failure, updated_at FROM review_status
                 WHERE label_failure IS NOT NULL
                   AND archived_at IS NULL
                 ORDER BY updated_at DESC
                """); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                String workspace = results.getString("workspace");
                String slug = results.getString("slug");
                long pr = results.getLong("pr_id");
                rows.add(new AttentionView(CODE, AttentionView.Severity.WARNING, workspace + "/" + slug + "#" + pr,
                        "Verdict label not set on " + workspace + "/" + slug + ": " + results.getString("label_failure"),
                        "/r/" + workspace + "/" + slug + "/" + pr)
                        .withSince(StoredTime.of(results, "updated_at")));
            }
        }
    }
}
