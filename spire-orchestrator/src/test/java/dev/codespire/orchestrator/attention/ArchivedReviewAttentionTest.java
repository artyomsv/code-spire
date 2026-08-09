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
import java.sql.Timestamp;
import java.time.Instant;

import static dev.codespire.orchestrator.readmodel.ReviewFixtures.REPO;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.WS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Archiving is a fix, so it has to clear the two things that would otherwise keep acting on a retired
 * review: its attention row and the retry sweep.
 *
 * <p>Both tests assert the RAISED state first. An absence-only test passes with the filter entirely
 * missing whenever the fixture never produced the state being filtered, which is exactly how two
 * assertions on this branch turned out to be vacuous.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ArchivedReviewAttentionTest {

    @Inject
    AttentionQueries queries;

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    /**
     * The panel's whole contract is that a row cannot outlive the state that produced it. A failed
     * review has no fix that makes it un-fail, so archiving it is the operator's resolution — and an
     * unfilterable REVIEW_FAILED would make it the panel's first permanently-lit row.
     */
    @Test
    void anArchivedFailedReviewStopsRaisingAttention() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedFailedReview(projection, pr);
        assertTrue(hasFailedRowFor(pr), "a failed review raises attention while live");

        projection.archiveReview(WS, REPO, pr);

        assertFalse(hasFailedRowFor(pr),
                "archiving is a fix; a permanently-lit row breaks the panel's contract");
    }

    /**
     * Archiving clears {@code retry_at}, but that is only the first of two defences. The sweep needs
     * its own filter for the row that acquires a due time afterwards — a result still in flight when
     * the archive landed, or a replica mid-dispatch.
     */
    @Test
    void anArchivedReviewIsNotSweptBackIntoThePipeline() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        projection.scheduleRetry(reviewId, 2, "TEST retry", Instant.now().minusSeconds(1));
        assertTrue(projection.claimDueRetries(Instant.now()).contains(reviewId),
                "a due review is swept while live");

        projection.updateStatus(reviewId, "completed", ReviewProjection.STAGE_DONE);
        projection.archiveReview(WS, REPO, pr);
        // Set retry_at directly, AFTER archiving, so this tests the sweep's own filter and not just
        // archive's clearing. A test that only exercised the clearing would pass with the filter gone.
        setRetryAtDirectly(reviewId, Instant.now().minusSeconds(1));

        // NOT isEmpty(): this module shares one database, and a sweep claims other suites' due rows.
        assertFalse(projection.claimDueRetries(Instant.now()).contains(reviewId));
    }

    /** Whether the panel currently names this PR in a REVIEW_FAILED row. */
    private boolean hasFailedRowFor(long pr) {
        String subject = WS + "/" + REPO + "#" + pr;
        return queries.collect().stream()
                .filter(v -> "REVIEW_FAILED".equals(v.code()))
                .map(AttentionView::subject)
                .anyMatch(subject::equals);
    }

    /** Put a due time back on an archived row, which no production path does — that is the point. */
    private void setRetryAtDirectly(String reviewId, Instant dueAt) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE review_status SET retry_at = ? WHERE review_id = ?")) {
            ps.setTimestamp(1, Timestamp.from(dueAt));
            ps.setString(2, reviewId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set retry_at for " + reviewId, e);
        }
    }
}
