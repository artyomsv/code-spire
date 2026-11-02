package dev.codespire.worker.pipeline;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewGenerated;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.ReviewRunBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.COMMIT;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.OVER_CAP_SUMMARY;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.ROUND;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.answer;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.finding;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.finish;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.prepareReReview;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.prior;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.priorA;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.priorB;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.verdict;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.verdictFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness reviews, step 6: the review worker finishes a harness review's answer with the API path's own parsers and
 * backstops.
 */
class HarnessReviewWorkerFinishTest {

    private HarnessReviewWorkerFixture fx;
    private HarnessReviewWorker worker;
    private List<IntegrationEvent> emitted;

    @BeforeEach
    void setUp() {
        fx = new HarnessReviewWorkerFixture();
        worker = fx.worker;
        emitted = fx.emitted;
    }

    @Test
    void aReviewAnswerBecomesFindings() {
        worker.finish(finish(ReviewRunBinding.Kind.REVIEW, answer(finding("src/C.java", 20), "")));

        HarnessReviewGenerated generated = fx.last(HarnessReviewGenerated.class);
        assertEquals(ROUND, generated.round());
        assertEquals(COMMIT, generated.commit());
        assertEquals(1, generated.result().findings().size());
        assertEquals("src/C.java", generated.result().findings().getFirst().path());
        assertEquals(List.of(), generated.verdicts(), "a first review judges nothing");
        assertFalse(generated.result().degraded());
    }

    @Test
    void aReReviewAnswerBecomesFindingsAndVerdicts() throws Exception {
        fx.seedPreparedPrior(true, priorA(), priorB());

        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, answer(finding("src/C.java", 20),
                verdict(1, "resolved") + "," + verdict(2, "still-open"))));

        HarnessReviewGenerated generated = fx.last(HarnessReviewGenerated.class);
        assertEquals(FindingVerdict.Status.RESOLVED, verdictFor(generated, "TEST-thread-1").status());
        assertEquals(FindingVerdict.Status.UNCHANGED, verdictFor(generated, "TEST-thread-2").status(),
                "the change never touched finding 2's line, so the backstop downgrades it");
        assertEquals(1, generated.result().findings().size(), "the new finding is kept");
        assertEquals("src/C.java", generated.result().findings().getFirst().path());
    }

    @Test
    void aReReviewWhoseCompareFailedDowngradesNothing() throws Exception {
        fx.seedPreparedPrior(false, priorA(), priorB());

        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, answer("", verdict(2, "still-open"))));

        HarnessReviewGenerated generated = fx.last(HarnessReviewGenerated.class);
        assertEquals(FindingVerdict.Status.STILL_OPEN, verdictFor(generated, "TEST-thread-2").status(),
                "no incremental diff, so the agent's verdict stands, as on the API path");
        assertEquals(0, fx.compareFetches, "a prepare that had no compare is not compared again");
    }

    @Test
    void theVerdictNumbersAreThoseThePrepareRendered() {
        worker.prepare(prepareReReview(prior(priorA(), priorB())));

        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, answer("", verdict(2, "resolved"))));

        HarnessReviewGenerated generated = fx.last(HarnessReviewGenerated.class);
        assertEquals(1, generated.verdicts().size());
        FindingVerdict second = generated.verdicts().getFirst();
        assertEquals("TEST-thread-2", second.threadRef(), "id 2 is the second finding the agent read");
        assertEquals("src/B.java", second.path());
        assertEquals(FindingVerdict.Status.RESOLVED, second.status());
    }

    @Test
    void aVerdictOutsideTheNumbersIsDropped() throws Exception {
        fx.seedPreparedPrior(false, priorA(), priorB());

        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE,
                answer("", verdict(0, "resolved") + "," + verdict(3, "resolved") + "," + verdict(1, "resolved"))));

        HarnessReviewGenerated generated = fx.last(HarnessReviewGenerated.class);
        assertEquals(1, generated.verdicts().size(), "ids 0 and 3 name no finding: " + generated.verdicts());
        assertEquals("TEST-thread-1", generated.verdicts().getFirst().threadRef());
    }

    @Test
    void aNewFindingOnAStillOpenAnchorIsDropped() throws Exception {
        fx.seedPreparedPrior(true, priorA());

        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE,
                answer(finding("src/A.java", 3) + "," + finding("src/C.java", 20), verdict(1, "still-open"))));

        HarnessReviewGenerated generated = fx.last(HarnessReviewGenerated.class);
        assertEquals(FindingVerdict.Status.STILL_OPEN, verdictFor(generated, "TEST-thread-1").status());
        assertEquals(1, generated.result().findings().size(), "the re-report at the open anchor is dropped");
        assertEquals("src/C.java", generated.result().findings().getFirst().path());
    }

    @Test
    void anOverCapAnswerIsADegradedReviewWithNoVerdicts() throws Exception {
        fx.seedPreparedPrior(true, priorA());

        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, null));

        HarnessReviewGenerated generated = fx.last(HarnessReviewGenerated.class);
        assertTrue(generated.result().degraded());
        assertEquals(OVER_CAP_SUMMARY, generated.result().summary());
        assertEquals(List.of(), generated.verdicts());
    }

    @Test
    void aProseAnswerIsADegradedReview() {
        worker.finish(finish(ReviewRunBinding.Kind.REVIEW, "TEST prose with no JSON in it"));

        HarnessReviewGenerated generated = fx.last(HarnessReviewGenerated.class);
        assertTrue(generated.result().degraded(), "a prose answer never reads as a clean review");
        assertEquals(List.of(), generated.result().findings());
    }

    @Test
    void aRedeliveredFinishReEmitsTheSameOutcome() throws Exception {
        fx.seedPreparedPrior(true, priorA(), priorB());

        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, answer(finding("src/C.java", 20), verdict(1, "resolved"))));
        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, answer("", verdict(1, "still-open"))));

        List<HarnessReviewGenerated> generated = emitted.stream().filter(HarnessReviewGenerated.class::isInstance)
                .map(HarnessReviewGenerated.class::cast).toList();
        assertEquals(2, generated.size(), "a redelivery re-emits, so a lost emit still converges");
        assertEquals(generated.get(0), generated.get(1), "the first outcome is persisted and re-emitted as is");
        assertEquals(1, fx.compareFetches, "the redelivery reads the persisted outcome and compares nothing");
    }

    @Test
    void aFinishWithoutItsPreparedPriorJudgesNothing() {
        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, answer(finding("src/C.java", 20), verdict(1, "resolved"))));

        HarnessReviewGenerated generated = fx.last(HarnessReviewGenerated.class);
        assertEquals(List.of(), generated.verdicts(), "with no numbering to read, no id can be trusted");
        assertEquals(1, generated.result().findings().size(), "the new findings still count");
    }

    @Test
    void aTransientFaultWhileFinishingPropagatesForReplay() {
        worker.idempotency = failingStore(new IllegalStateException("TEST-db", new SQLException("TEST-connection")));

        assertThrows(RuntimeException.class, () -> worker.finish(finish(ReviewRunBinding.Kind.REVIEW,
                answer(finding("src/C.java", 20), ""))), "the record dead-letters with its paid answer, for a replay");
        assertTrue(emitted.isEmpty(), "a database hiccup never fails the review: " + emitted);
    }

    @Test
    void aNonRetryableFinishFailureFailsTheReview() {
        worker.idempotency = failingStore(new IllegalArgumentException("TEST-permanent"));

        worker.finish(finish(ReviewRunBinding.Kind.REVIEW, answer(finding("src/C.java", 20), "")));

        ReviewFailed failed = fx.last(ReviewFailed.class);
        assertEquals("harness-finish", failed.phase());
        assertFalse(failed.retryable(), "a retry would meet the same run id and never answer");
        assertEquals(1, failed.attempt());
        assertFalse(failed.credentialRejected());
    }

    /** Every store method a finish reaches throws the given fault. */
    private static CommentIdempotencyStore failingStore(RuntimeException fault) {
        return new CommentIdempotencyStore() {
            @Override
            public Claim claim(String reviewId, String commit, String anchorKey) {
                throw fault;
            }

            @Override
            public void markPosted(String reviewId, String commit, String anchorKey, String postedRef) {
                throw fault;
            }

            @Override
            public Map<String, String> postedFor(String reviewId, String commit) {
                throw fault;
            }
        };
    }
}
