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

    @Inject
    LabelFailures labelFailures;

    @Test
    void aLabelFailureRaisesAWarningNamingTheRepositoryAndTheReason() {
        long pr = reviewing();
        labelFailures.record(ReviewFixtures.reviewIdFor(pr), head(pr), "TEST-HTTP-403");

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
        labelFailures.record(reviewId, head(pr), "TEST-HTTP-403");
        assertEquals(1, rowsFor(pr).size(), "raised");

        labelFailures.record(reviewId, head(pr), null);

        assertTrue(rowsFor(pr).isEmpty());
    }

    @Test
    void aSupersededRunsReportWritesNothing() {
        long pr = reviewing();

        labelFailures.record(ReviewFixtures.reviewIdFor(pr), "TEST-OLD-HEAD", "TEST-HTTP-403");

        assertTrue(rowsFor(pr).isEmpty());
    }

    /**
     * A skipped run's ci-failed label report (V121) writes only for the round its header holds: the seeded header has
     * no round (0), so a report of round 1, as from an earlier run after a re-run, writes nothing, and round 0's does.
     */
    @Test
    void aSkippedRunsReportWritesOnlyForItsOwnRound() {
        long pr = reviewing();
        String reviewId = ReviewFixtures.reviewIdFor(pr);

        labelFailures.recordForRun(new LabelFailures.Run(reviewId, head(pr), 1), "TEST-HTTP-403");
        assertTrue(rowsFor(pr).isEmpty(), "another round's report");

        labelFailures.recordForRun(new LabelFailures.Run(reviewId, head(pr), 0), "TEST-HTTP-403");
        assertEquals(1, rowsFor(pr).size(), "its own round's report");
    }

    @Test
    void anArchivedReviewRaisesNothing() {
        long pr = reviewing();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        // A label failure belongs to a posted review, and a review still reviewing cannot be archived.
        projection.updateStatus(reviewId, "completed", ReviewProjection.STAGE_DONE);
        labelFailures.record(reviewId, head(pr), "TEST-HTTP-403");
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
