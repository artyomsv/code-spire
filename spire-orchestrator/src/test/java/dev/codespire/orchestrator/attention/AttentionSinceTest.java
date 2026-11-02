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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static dev.codespire.orchestrator.readmodel.ReviewFixtures.REPO;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.WS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A row's {@code since} is read from stored state, never from the clock: {@link AttentionBroadcaster} pushes only when
 * the evaluated list changes, so a time that moved with each sweep would push on every sweep.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class AttentionSinceTest {

    @Inject
    AttentionQueries queries;

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    /** The refusal is written beside the status change that bumps updated_at, so that is when the refusal began. */
    @Test
    void aRefusedReviewRowCarriesItsReviewsTime() {
        long pr = refusedReview();

        AttentionView row = rowFor("REVIEW_HARNESS_REFUSED", pr);

        assertNotNull(row.since(), "a refused review has a stored time");
        assertEquals(updatedAt(pr), row.since());
    }

    @Test
    void aFailedReviewRowCarriesItsReviewsTime() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedFailedReview(projection, pr);

        AttentionView row = rowFor("REVIEW_FAILED", pr);

        assertEquals(updatedAt(pr), row.since());
    }

    /** A refused row is seeded first, so the comparison is over a list that carries at least one time. */
    @Test
    void twoEvaluationsOfUnchangedStateAreEqual() {
        long pr = refusedReview();
        assertNotNull(rowFor("REVIEW_HARNESS_REFUSED", pr).since());

        List<AttentionView> first = queries.collect();
        List<AttentionView> second = queries.collect();

        assertEquals(first, second);
    }

    /** The refused review is the newest review problem, so it is the first warning in the bell. */
    @Test
    void aJustRefusedReviewIsTheFirstWarning() {
        long pr = refusedReview();

        AttentionView firstWarning = queries.collect().stream()
                .filter(view -> view.severity() == AttentionView.Severity.WARNING)
                .findFirst().orElseThrow();

        assertEquals(WS + "/" + REPO + "#" + pr, firstWarning.subject(), firstWarning.toString());
    }

    private long refusedReview() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedReviewingReview(projection, pr);
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        projection.updateStatus(reviewId, "refused", ReviewProjection.STAGE_REVIEW);
        projection.setHarnessRefusal(reviewId, "codex: subscription_unavailable");
        return pr;
    }

    private AttentionView rowFor(String code, long pr) {
        String subject = WS + "/" + REPO + "#" + pr;
        List<AttentionView> rows = queries.collect().stream()
                .filter(view -> code.equals(view.code()) && subject.equals(view.subject()))
                .toList();
        assertTrue(rows.size() == 1, rows.toString());
        return rows.getFirst();
    }

    /** Raw SQL, so the expected time does not come from the query under test. */
    private Instant updatedAt(long pr) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT updated_at FROM review_status WHERE review_id = ?")) {
            ps.setString(1, ReviewFixtures.reviewIdFor(pr));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the review exists");
                return rs.getTimestamp("updated_at").toInstant();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read updated_at for PR " + pr, e);
        }
    }
}
