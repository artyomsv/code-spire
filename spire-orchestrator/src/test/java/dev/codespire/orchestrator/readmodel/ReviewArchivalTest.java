package dev.codespire.orchestrator.readmodel;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static dev.codespire.orchestrator.readmodel.ReviewFixtures.REPO;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.WS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Archiving replaces the hard delete. The properties asserted here are the ones whose failure is
 * silent: money that vanishes with a row removed for being clutter, an archived review whose own
 * detail page then cannot report what it spent, and a four-valued outcome that would otherwise
 * degrade into a boolean unable to tell an operator which refusal they hit.
 */
@QuarkusTest
class ReviewArchivalTest {

    @Inject
    ReviewProjection projection;

    @Test
    void archivingKeepsEveryChargeRow() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        String id = ReviewFixtures.reviewIdFor(pr);
        long before = projection.costOf(id).knownCostMillicents();
        assertTrue(before > 0, "the fixture must record real spend or this test proves nothing");

        assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview(WS, REPO, pr));

        assertEquals(before, projection.costOf(id).knownCostMillicents(),
                "archiving must not destroy recorded spend");
    }

    @Test
    void anArchivedReviewStillShowsItsOwnCostModelAndLines() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        String id = ReviewFixtures.reviewIdFor(pr);
        projection.archiveReview(WS, REPO, pr);

        assertTrue(projection.costOf(id).knownCostMillicents() > 0);
        assertFalse(projection.chargeLines(id).isEmpty(), "its charge lines are still readable");
        assertNotNull(projection.costOf(id).lastModel(), "and so is the model that ran it");
    }

    @Test
    void archivingPreservesStatusAndPrState() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        projection.archiveReview(WS, REPO, pr);

        ReviewDetail detail = projection.loadDetail(WS, REPO, pr).orElseThrow();
        assertEquals("completed", detail.status());
        assertEquals("OPEN", detail.prState());
        assertNotNull(detail.archivedAt(), "and it knows it is archived");
    }

    @Test
    void archiveDistinguishesAllFourOutcomes() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview(WS, REPO, pr));
        assertEquals(ArchiveOutcome.ALREADY_ARCHIVED, projection.archiveReview(WS, REPO, pr));
        assertEquals(ArchiveOutcome.NOT_FOUND, projection.archiveReview(WS, REPO, ReviewFixtures.newPr()));

        long running = ReviewFixtures.newPr();
        ReviewFixtures.seedReviewingReview(projection, running);
        assertEquals(ArchiveOutcome.STILL_RUNNING, projection.archiveReview(WS, REPO, running));
    }

    @Test
    void archivingClearsTheRetryScheduleAndTheAnsweringFlag() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        String id = ReviewFixtures.reviewIdFor(pr);
        projection.scheduleRetry(id, 2, "TEST retry", Instant.now().plusSeconds(60));
        projection.setAnswering(id, true);
        // scheduleRetry puts the row back into 'reviewing', which archive refuses — restore the
        // terminal outcome so this test exercises the clearing rather than the running guard.
        projection.updateStatus(id, "completed", ReviewProjection.STAGE_DONE);

        assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview(WS, REPO, pr));

        // NOT isEmpty(): this module shares one database, and a sweep would claim other tests' due rows.
        assertFalse(projection.claimDueRetries(Instant.now().plusSeconds(120)).contains(id));
        assertFalse(projection.loadDetail(WS, REPO, pr).orElseThrow().answering());
    }

    @Test
    void unarchiveRestoresTheReviewToTheLiveList() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        projection.archiveReview(WS, REPO, pr);
        assertFalse(projection.listSummaries(false).stream().anyMatch(s -> s.pr() == pr),
                "precondition: archiving took it out of the live list");

        assertTrue(projection.unarchiveReview(WS, REPO, pr));

        assertTrue(projection.listSummaries(false).stream().anyMatch(s -> s.pr() == pr));
    }

    /**
     * The gate every resurrection path consults. Asserted in both directions: a one-directional test
     * passes against a method hard-wired to its expected answer.
     */
    @Test
    void archivedAnswersForTheRowsStateAndNotForALiveOne() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        String id = ReviewFixtures.reviewIdFor(pr);
        assertFalse(projection.archived(id), "a live review is not archived");

        projection.archiveReview(WS, REPO, pr);

        assertTrue(projection.archived(id));
        assertFalse(projection.archived(ReviewFixtures.reviewIdFor(ReviewFixtures.newPr())),
                "and a review that does not exist is not archived either");
    }
}
