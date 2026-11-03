package dev.codespire.worker.pipeline;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewGenerated;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewPrepared;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.review.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.COMMIT;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.PRIOR_HEAD;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.RENAME_DIFF;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.ROUND;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.answer;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.file;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.finish;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.prepareReReview;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.prepareReview;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.prior;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.priorA;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.priorB;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.verdict;
import static dev.codespire.worker.pipeline.HarnessReviewWorkerFixture.verdictFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Harness reviews, step 6: the review worker prepares a harness review's input files. */
class HarnessReviewWorkerPrepareTest {

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
    void aFirstReviewIsPreparedWithItsContext() throws Exception {
        String ref = fx.contextRef(new ContextItem("ISSUE", "TEST-ISSUE-TITLE", "TEST-ISSUE-BODY",
                "https://forge.example.test/TEST-work/app/issues/1"));

        worker.prepare(prepareReview(ref));

        HarnessReviewPrepared prepared = fx.last(HarnessReviewPrepared.class);
        assertEquals(ReviewRunBinding.Kind.REVIEW, prepared.kind());
        assertEquals(ROUND, prepared.round(), "the round is echoed, so the launch can check it");
        assertEquals(COMMIT, prepared.commit());
        String context = file(prepared.inputs(), HarnessReviewInputs.CONTEXT);
        assertNotNull(context);
        assertTrue(context.contains("TEST-ISSUE-BODY"), context);
        assertTrue(context.contains("TEST-TITLE"), context);
        assertNull(file(prepared.inputs(), HarnessReviewInputs.PRIOR_FINDINGS));
        assertEquals(0, fx.compareFetches, "a first review compares nothing");
    }

    @Test
    void aReviewWithNoContextRefStillGetsAContextFile() {
        worker.prepare(prepareReview(null));

        String context = file(fx.last(HarnessReviewPrepared.class).inputs(), HarnessReviewInputs.CONTEXT);
        assertNotNull(context, "the agent is told to read it, so it is always there");
        assertTrue(context.contains("TEST-TITLE"), context);
    }

    @Test
    void aReReviewNumbersItsPriorFindingsFromOne() {
        PriorFinding untracked = new PriorFinding("src/B.java", 9, Severity.MINOR, "TEST-SECOND-FINDING", null);

        worker.prepare(prepareReReview(prior(priorA(), untracked)));

        HarnessReviewPrepared prepared = fx.last(HarnessReviewPrepared.class);
        assertEquals(ReviewRunBinding.Kind.RECONCILE, prepared.kind());
        String priorFile = file(prepared.inputs(), HarnessReviewInputs.PRIOR_FINDINGS);
        assertNotNull(priorFile);
        assertTrue(priorFile.contains("1. [MAJOR] src/A.java:3"), priorFile);
        assertTrue(priorFile.contains("2. [MINOR] src/B.java:9"), priorFile);
        assertTrue(priorFile.contains("[reviewer] please fix this"), "the one thread's transcript: " + priorFile);
        assertEquals(priorFile.indexOf("please fix this"), priorFile.lastIndexOf("please fix this"),
                "only the finding with a thread has a transcript");
        assertTrue(priorFile.contains("git diff " + PRIOR_HEAD + " HEAD"), priorFile);
        assertNotNull(file(prepared.inputs(), HarnessReviewInputs.CONTEXT), "a re-review gets its context too");
    }

    @Test
    void aReReviewWhoseCompareFailsSaysTheHeadIsGone() {
        fx.compareFailure = new IllegalStateException("TEST-compare-404");

        worker.prepare(prepareReReview(prior(priorA(), priorB())));

        String priorFile = file(fx.last(HarnessReviewPrepared.class).inputs(), HarnessReviewInputs.PRIOR_FINDINGS);
        assertTrue(priorFile.contains("no longer in the pull request's history"), priorFile);
        assertFalse(priorFile.contains("git diff"), priorFile);
    }

    @Test
    void aRenamedFileIsJudgedAtItsNewPath() {
        fx.compareDiff = RENAME_DIFF;
        PriorFinding old = new PriorFinding("src/Old.java", 5, Severity.MAJOR, "TEST-RENAMED", "TEST-thread-1");

        worker.prepare(prepareReReview(prior(old)));
        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, answer("", verdict(1, "resolved"))));

        String priorFile = file(fx.last(HarnessReviewPrepared.class).inputs(), HarnessReviewInputs.PRIOR_FINDINGS);
        assertTrue(priorFile.contains("src/New.java:5"), priorFile);
        assertFalse(priorFile.contains("src/Old.java:5"), priorFile);
        FindingVerdict judged = verdictFor(fx.last(HarnessReviewGenerated.class), "TEST-thread-1");
        assertEquals("src/New.java", judged.path(), "the verdict carries the path the agent judged");
    }

    @Test
    void aPreparedReviewWhoseHeadMovedIsAbandoned() {
        fx.head = "c".repeat(40);

        worker.prepare(prepareReview(null));
        worker.prepare(prepareReReview(prior(priorA())));

        assertTrue(emitted.isEmpty(), "a moved head is abandoned quietly: " + emitted);
    }

    @Test
    void aFailedPrepareIsARetryableReviewFailure() {
        fx.prFailure = new UncheckedIOException(new IOException("TEST-transient"));

        worker.prepare(prepareReview(null));

        HarnessReviewFailed failed = fx.last(HarnessReviewFailed.class);
        assertEquals("harness-prepare", failed.phase());
        assertTrue(failed.retryable(), "a transient read is retried on the same round");
        assertEquals(COMMIT, failed.commit());
        assertEquals(ROUND, failed.round(), "the round is carried, so a late failure never fails a newer round");
        assertTrue(emitted.stream().noneMatch(ReviewFailed.class::isInstance),
                "a plain ReviewFailed would bypass the round check: " + emitted);
        assertTrue(emitted.stream().noneMatch(HarnessReviewPrepared.class::isInstance));
    }

    @Test
    void aRedeliveredPrepareKeepsItsNumbering() {
        worker.prepare(prepareReReview(prior(priorA(), priorB())));
        worker.prepare(prepareReReview(prior(priorB(), priorA()))); // a redelivery that reads the list in another order

        List<HarnessReviewPrepared> prepared = emitted.stream().filter(HarnessReviewPrepared.class::isInstance)
                .map(HarnessReviewPrepared.class::cast).toList();
        assertEquals(2, prepared.size());
        String second = file(prepared.get(1).inputs(), HarnessReviewInputs.PRIOR_FINDINGS);
        assertTrue(second.contains("1. [MAJOR] src/A.java:3"), second);
        assertTrue(second.contains("2. [MINOR] src/B.java:9"), second);
        assertEquals(prepared.get(0).inputs(), prepared.get(1).inputs());
        assertEquals(1, fx.compareFetches, "the numbering is read back, never recomputed");
    }
}
