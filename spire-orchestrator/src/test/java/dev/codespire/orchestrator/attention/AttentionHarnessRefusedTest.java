package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.orchestrator.readmodel.ReviewFixtures;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static dev.codespire.orchestrator.readmodel.ReviewFixtures.REPO;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.WS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A refused harness review raises attention naming its harness and its reason (harness reviews, step 5): a spent pool
 * must never become an unnoticed stall. Each row carries its own reason, so it is its own query rather than one of the
 * fixed-message per-review rows.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class AttentionHarnessRefusedTest {

    private static final String CODE = "REVIEW_HARNESS_REFUSED";

    @Inject
    AttentionQueries queries;

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    @Test
    void aRefusedHarnessReviewRaisesAttention() {
        long pr = refusedReview("codex: subscription_unavailable");

        List<AttentionView> rows = rowsFor(pr);

        assertEquals(1, rows.size(), rows.toString());
        AttentionView row = rows.getFirst();
        assertEquals("Harness review refused: codex: subscription_unavailable", row.message());
        assertEquals("/r/" + WS + "/" + REPO + "/" + pr, row.action(), "it links the review as the other review rows do");
    }

    /** A spend-cap refusal names no harness: the cap's own row covers it, so this raises nothing. */
    @Test
    void aRefusedReviewWithoutAHarnessReasonRaisesNothing() {
        long pr = refusedReview(null);

        assertTrue(rowsFor(pr).isEmpty());
    }

    /**
     * The reason outlives its refusal until the next round reaches ContextAssembled, so a re-run still in its diff or
     * context stage carries the old one. Only a review that is refused now raises the row.
     */
    @Test
    void aReviewNoLongerRefusedRaisesNothing() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedReviewingReview(projection, pr);
        setHarnessRefusal(ReviewFixtures.reviewIdFor(pr), "codex: subscription_unavailable");

        assertTrue(rowsFor(pr).isEmpty());
    }

    /** Archiving is the operator's resolution, as for every other review row. Raised first, so this is not vacuous. */
    @Test
    void anArchivedReviewRaisesNothing() {
        long pr = refusedReview("codex: subscription_unavailable");
        assertEquals(1, rowsFor(pr).size(), "raised while live");

        projection.archiveReview(WS, REPO, pr);

        assertTrue(rowsFor(pr).isEmpty());
    }

    private long refusedReview(String harnessRefusal) {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedReviewingReview(projection, pr);
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        projection.updateStatus(reviewId, "refused", ReviewProjection.STAGE_REVIEW);
        setHarnessRefusal(reviewId, harnessRefusal);
        return pr;
    }

    private List<AttentionView> rowsFor(long pr) {
        String subject = WS + "/" + REPO + "#" + pr;
        return queries.collect().stream()
                .filter(view -> CODE.equals(view.code()))
                .filter(view -> subject.equals(view.subject()))
                .toList();
    }

    /** Raw SQL, so this test does not depend on the projection's writer it would otherwise share a defect with. */
    private void setHarnessRefusal(String reviewId, String reason) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE review_status SET harness_refusal = ? WHERE review_id = ?")) {
            ps.setString(1, reason);
            ps.setString(2, reviewId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set harness_refusal for " + reviewId, e);
        }
    }
}
