package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.orchestrator.readmodel.ArchiveOutcome;
import dev.codespire.orchestrator.readmodel.ReviewFixtures;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.codespire.orchestrator.readmodel.ReviewFixtures.REPO;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.WS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A posted review whose verdict label failed raises REVIEW_LABEL_FAILED with the forge's reason (verdict labels,
 * V120); the next report of the same head clears it, and a superseded run's report cannot write it.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class AttentionLabelFailedTest {

    private static final String CODE = "REVIEW_LABEL_FAILED";

    @Inject
    AttentionQueries queries;

    @Inject
    ReviewProjection projection;

    @Test
    void aLabelFailureRaisesAWarningNamingTheRepositoryAndTheReason() {
        long pr = reviewing();
        projection.recordLabelFailure(ReviewFixtures.reviewIdFor(pr), head(pr), "TEST-HTTP-403");

        List<AttentionView> rows = rowsFor(pr);

        assertEquals(1, rows.size(), rows.toString());
        AttentionView row = rows.getFirst();
        assertEquals(AttentionView.Severity.WARNING, row.severity());
        assertEquals("Verdict label not set on " + WS + "/" + REPO + ": TEST-HTTP-403", row.message());
        assertEquals("/r/" + WS + "/" + REPO + "/" + pr, row.action());
    }

    /** Raised first, so the clear is not vacuous. */
    @Test
    void theNextReportWithoutAFailureClearsIt() {
        long pr = reviewing();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        projection.recordLabelFailure(reviewId, head(pr), "TEST-HTTP-403");
        assertEquals(1, rowsFor(pr).size(), "raised");

        projection.recordLabelFailure(reviewId, head(pr), null);

        assertTrue(rowsFor(pr).isEmpty());
    }

    @Test
    void aSupersededRunsReportWritesNothing() {
        long pr = reviewing();

        projection.recordLabelFailure(ReviewFixtures.reviewIdFor(pr), "TEST-OLD-HEAD", "TEST-HTTP-403");

        assertTrue(rowsFor(pr).isEmpty());
    }

    @Test
    void anArchivedReviewRaisesNothing() {
        long pr = reviewing();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        // A label failure belongs to a posted review, and a review still reviewing cannot be archived.
        projection.updateStatus(reviewId, "completed", ReviewProjection.STAGE_DONE);
        projection.recordLabelFailure(reviewId, head(pr), "TEST-HTTP-403");
        assertEquals(1, rowsFor(pr).size(), "raised while live");

        assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview(WS, REPO, pr));

        assertTrue(rowsFor(pr).isEmpty());
    }

    private long reviewing() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedReviewingReview(projection, pr);
        return pr;
    }

    /** The head {@link ReviewFixtures} registers for a seeded review. */
    private static String head(long pr) {
        return "TESTSHA" + pr;
    }

    private List<AttentionView> rowsFor(long pr) {
        String subject = WS + "/" + REPO + "#" + pr;
        return queries.collect().stream()
                .filter(view -> CODE.equals(view.code()))
                .filter(view -> subject.equals(view.subject()))
                .toList();
    }
}
