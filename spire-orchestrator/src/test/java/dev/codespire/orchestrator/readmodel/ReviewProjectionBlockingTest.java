package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.repository.BlockApprovalAt;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The open-finding count at a repository's "Block approval at" level (V115, ADR-047), against a real Postgres.
 * Split from {@link ReviewProjectionTest}; the review it reads is built the same way.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ReviewProjectionBlockingTest {

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    private static final RepoRef REPO = new RepoRef("acme", "web");

    private String completedReview(long pr, List<Finding> findings) {
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "TEST title", "TEST-author", "TEST-acc", "feature", "main",
                "cafe123", "https://x/pr/" + pr, "github", "reviewing", ReviewProjection.STAGE_DIFF);
        ReviewResult result = new ReviewResult(findings, "TEST summary", ModelUsage.of("TEST-MODEL", 1, 1));
        projection.recordOutcome(id, result, ReviewProjection.STAGE_DONE);
        return id;
    }

    private static List<Finding> oneOfEachBelowBlocker() {
        return List.of(
                new Finding("src/Major.java", new LineRange(1, 1), Severity.MAJOR, "TEST major", null),
                new Finding("src/Minor.java", new LineRange(2, 2), Severity.MINOR, "TEST minor", null),
                new Finding("src/Info.java", new LineRange(3, 3), Severity.INFO, "TEST info", null),
                new Finding("src/Nit.java", new LineRange(4, 4), Severity.NIT, "TEST nit", null));
    }

    @Test
    void openBlockingCountsTheOpenFindingsAtOrAboveTheLevel() {
        String id = completedReview(4211L, oneOfEachBelowBlocker());

        assertEquals(0, projection.openBlocking(id, BlockApprovalAt.BLOCKER), "a MAJOR does not block at BLOCKER");
        assertEquals(projection.openBlockers(id), projection.openBlocking(id, BlockApprovalAt.BLOCKER),
                "BLOCKER is the open blocker count, exactly");
        assertEquals(1, projection.openBlocking(id, BlockApprovalAt.MAJOR), "the MAJOR blocks at MAJOR, the MINOR does not");
        assertEquals(2, projection.openBlocking(id, BlockApprovalAt.MINOR), "MAJOR and MINOR block at MINOR; INFO and NIT never");
        assertEquals(0, projection.openBlocking("review::acme/web#0", BlockApprovalAt.MINOR), "a missing row blocks nothing");
    }

    @Test
    void aStillOpenPriorMajorBlocksAtMajor() {
        // The count reads the reconciled open set, not this run's findings alone: the only MAJOR is a prior one.
        long pr = 4212L;
        String id = completedReview(pr, List.of());
        PriorFinding priorMajor = new PriorFinding("src/B.java", 8, Severity.MAJOR, "TEST prior major", "t-prior-major");
        projection.recordReconciliation(id,
                List.of(new FindingVerdict("t-prior-major", "src/B.java", 8, FindingVerdict.Status.STILL_OPEN, "TEST still open")),
                List.of(priorMajor));

        assertEquals(1, projection.openBlocking(id, BlockApprovalAt.MAJOR));
        assertEquals(0, projection.openBlocking(id, BlockApprovalAt.BLOCKER));
    }

    @Test
    void theDetailCountsBlockingAtItsRepositorysLevel() throws SQLException {
        long pr = 4213L;
        String id = completedReview(pr, oneOfEachBelowBlocker());
        java.util.UUID repository = java.util.UUID.randomUUID();
        execute("INSERT INTO repository (id, scm_type, forge_origin, workspace, slug, block_approval_at)"
                + " VALUES (?, 'github', 'https://TEST-forge.example.test', ?, 'TEST-repo', 'MINOR')",
                repository, "TEST-V115-" + repository);
        try {
            execute("UPDATE review_status SET repository_id = ? WHERE review_id = ?", repository, id);
            ReviewDetail atMinor = projection.loadDetail("acme", "web", pr).orElseThrow();
            assertEquals("MINOR", atMinor.blockApprovalAt());
            assertEquals(2, atMinor.openBlocking());
            assertEquals(0, atMinor.openBlockers(), "the blocker count keeps its meaning");

            execute("UPDATE repository SET block_approval_at = 'MAJOR' WHERE id = ?", repository);
            ReviewDetail atMajor = projection.loadDetail("acme", "web", pr).orElseThrow();
            assertEquals("MAJOR", atMajor.blockApprovalAt());
            assertEquals(1, atMajor.openBlocking());
        } finally {
            execute("UPDATE review_status SET repository_id = NULL WHERE review_id = ?", id);
            execute("DELETE FROM repository WHERE id = ?", repository);
        }
    }

    @Test
    void aReviewWithoutAKnownRepositoryBlocksAtBlocker() {
        long pr = 4214L;
        completedReview(pr, oneOfEachBelowBlocker());
        ReviewDetail detail = projection.loadDetail("acme", "web", pr).orElseThrow();
        assertEquals("BLOCKER", detail.blockApprovalAt());
        assertEquals(0, detail.openBlocking());
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                ps.setObject(i + 1, values[i]);
            }
            ps.executeUpdate();
        }
    }
}
