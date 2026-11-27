package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.command.ActionCommand.LabelChecksFailed;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.RepoRef;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A dashboard Re-run clears the worker's claims but keeps a skipped run's ci-failed receipt (review of PR #458,
 * code-quality/H-1). Without it, an old run's dead-lettered {@code LabelChecksFailed}, replayed after the re-run posted
 * its verdict on the same head, finds a fresh claim and labels {@code review: ci-failed} over that verdict. The worker
 * half (a recorded receipt makes no forge call) is {@code ChecksFailedLabelTest}.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class RerunKeepsLabelReceiptsTest {

    private static final RepoRef REPO = new RepoRef("acme", "web");
    private static final String COMMIT = "sha-receipt";

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    @Test
    void aRerunDropsTheCachedResultButKeepsTheRunsLabelReceipt() throws SQLException {
        String id = ReviewIds.reviewId(REPO, 4180L);
        String receipt = LabelChecksFailed.RECEIPT_PREFIX + 3;
        createWorkerTable();
        insertClaim(id, "LLM");
        insertClaim(id, receipt);

        projection.clearWorkerIdempotency(id);

        assertEquals(List.of(receipt), anchorsOf(id), "only the ci-failed receipt survives the re-run");
    }

    /** The same shape ReviewProjectionTest creates, so either test may create it first. */
    private void createWorkerTable() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS worker");
            st.execute("CREATE TABLE IF NOT EXISTS worker.comment_idempotency ("
                    + "review_id text NOT NULL, commit text NOT NULL, anchor_key text NOT NULL, "
                    + "comment_id text, posted_at timestamptz, created_at timestamptz DEFAULT now(), "
                    + "PRIMARY KEY (review_id, commit, anchor_key))");
        }
    }

    private void insertClaim(String reviewId, String anchorKey) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO worker.comment_idempotency (review_id, commit, anchor_key) VALUES (?, ?, ?)")) {
            ps.setString(1, reviewId);
            ps.setString(2, COMMIT);
            ps.setString(3, anchorKey);
            ps.executeUpdate();
        }
    }

    private List<String> anchorsOf(String reviewId) throws SQLException {
        List<String> anchors = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT anchor_key FROM worker.comment_idempotency WHERE review_id = ? ORDER BY anchor_key")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    anchors.add(rs.getString(1));
                }
            }
        }
        return anchors;
    }
}
