package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.PriorRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A clipped reconcile never resolves a finding it did not see, on the first run or on a replay (PR 456). */
class ReviewWorkerInputLimitReconcileTest extends ReviewWorkerInputLimitFixture {

    // --- a clipped reconcile ---

    /** A clipped re-review must not resolve a finding on a file the model did not fully see, and says it was partial. */
    @Test
    void aClippedReconcileLeavesAFindingOnAnOmittedFileUnchanged() {
        compareDiff = fileDiff("src/TEST-seen.java", 10) + fileDiff("src/Demo.java", 3_000); // over the 12,000

        worker.generateReview(followUp(priorAtDemo5()));

        ReviewGenerated generated = lastReviewGenerated();
        assertEquals(FindingVerdict.Status.UNCHANGED, generated.verdicts().getFirst().status(),
                "src/Demo.java was cut from the reconcile diff");
        assertTrue(generated.result().truncated(), "the clipped reconcile reaches the posted note");
        assertTrue(generated.result().omittedFiles().contains("src/Demo.java"), generated.result().omittedFiles().toString());
    }

    @Test
    void aReplayedReconcileKeepsItsClipping() {
        compareDiff = fileDiff("src/TEST-seen.java", 10) + fileDiff("src/Demo.java", 3_000);
        PriorRun prior = priorAtDemo5();
        worker.generateReview(followUp(prior));
        // The review call never finished: only the reconcile claim stands when the command is redelivered.
        idempotency.markPosted(REVIEW_ID, COMMIT, ReviewWorker.LLM_KEY, null); // a crashed claim, reclaimable

        worker.generateReview(followUp(prior));

        assertTrue(lastReviewGenerated().result().omittedFiles().contains("src/Demo.java"),
                "the persisted reconcile outcome still names the cut file");
    }

    /**
     * PR 456, H-3: a large section, then a rename of the finding's file to a path Git C-quotes, cut before the rename.
     * The model says the finding is fixed; the verdict stays UNCHANGED on the first run, on a replay of the persisted
     * outcome, and through posting, where an UNCHANGED verdict never touches its thread.
     */
    @Test
    void aQuotedRenamePastTheCutIsNeverResolvedNorOnReplay() {
        compareDiff = fileDiff("src/TEST-big.java", 3_000) + QUOTED_RENAME_OF_DEMO;
        PriorRun prior = priorAtDemo5();

        worker.generateReview(followUp(prior));
        assertEquals(FindingVerdict.Status.UNCHANGED, lastReviewGenerated().verdicts().getFirst().status(),
                "the rename was never sent, whatever the model answered");

        idempotency.markPosted(REVIEW_ID, COMMIT, ReviewWorker.LLM_KEY, null); // redelivered: the reconcile replays
        worker.generateReview(followUp(prior));
        ReviewGenerated replayed = lastReviewGenerated();
        assertEquals(FindingVerdict.Status.UNCHANGED, replayed.verdicts().getFirst().status(), "the persisted verdict");

        worker.postComments(new PostComments(REVIEW_ID, REPO, 9, COMMIT, replayed.result(), null,
                replayed.verdicts(), null));
        assertTrue(sink.resolvedThreads.isEmpty(), "the finding's thread is not resolved");
        assertTrue(sink.repliedThreads.isEmpty(), "nor replied to");
    }

    /** Renames the prior finding's file to a path with a non-ASCII byte, which Git writes C-quoted. */
    private static final String QUOTED_RENAME_OF_DEMO = """
            diff --git a/src/Demo.java "b/src/TEST-Demo-\\303\\251.java"
            similarity index 90%
            rename from src/Demo.java
            rename to "src/TEST-Demo-\\303\\251.java"
            --- a/src/Demo.java
            +++ "b/src/TEST-Demo-\\303\\251.java"
            @@ -5,1 +5,1 @@
            -leak();
            +close();
            """;
}
