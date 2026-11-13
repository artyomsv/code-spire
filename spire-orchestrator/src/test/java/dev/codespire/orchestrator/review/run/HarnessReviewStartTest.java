package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.orchestrator.llm.ReviewRuns;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a review runs on its repository's harness, and that a harness review is first sent to the worker to prepare
 * its inputs, a re-review as a reconcile (harness reviews, step 6). The dispatch itself is {@link HarnessReviewLaunch}'s
 * ({@code HarnessReviewLaunchTest}).
 *
 * <p>A plain unit test: every collaborator the path reaches is a fake overriding every method it calls, because an
 * un-overridden one opens a real database from a unit test (CLAUDE.md, saga test fakes). The dispatch fake is kept
 * to prove that the start never dispatches.
 */
class HarnessReviewStartTest extends HarnessReviewStartFixture {

    @Test
    void aRepositoryWithoutAHarnessIsNotAHarnessRun() {
        harness = Optional.empty();

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertInstanceOf(HarnessReviewStart.NotHarness.class, decision);
        assertNothingSent();
    }

    @Test
    void aFirstReviewIsPreparedWithItsContext() {
        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Preparing("TEST-harness"), decision);
        ActionCommand.PrepareHarnessReview prepare = onlyPrepare();
        assertEquals(REVIEW_ID, prepare.reviewId());
        assertEquals(REPO, prepare.repo());
        assertEquals(PR, prepare.prId());
        assertEquals(HEAD, prepare.commit());
        assertEquals("TEST-context-ref", prepare.contextRef());
        assertEquals("TEST-packed-scm", prepare.scmCredential());
        assertNull(prepare.priorRun());
        assertEquals(CURRENT_RUN, prepare.round());
        assertEquals(ReviewRunBinding.Kind.REVIEW, prepare.kind());
        assertTrue(dispatched.isEmpty(), "the run is dispatched once the worker has prepared it: " + dispatched);
    }

    /** Step 6 replaces step 5's "a re-review is not a harness run": the prior findings go to the worker to number. */
    @Test
    void aReReviewIsPreparedAsAReconcile() {
        prior = Optional.of(PRIOR_WITH_FINDING);

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Preparing("TEST-harness"), decision);
        ActionCommand.PrepareHarnessReview prepare = onlyPrepare();
        assertEquals(ReviewRunBinding.Kind.RECONCILE, prepare.kind());
        assertEquals(PRIOR_WITH_FINDING, prepare.priorRun());
        assertEquals(CURRENT_RUN, prepare.round());
        assertTrue(dispatched.isEmpty(), dispatched.toString());
    }

    /** The API path skips reconcile when nothing is open, so an empty reconcile is never charged. */
    @Test
    void aReReviewWithNoOpenFindingsIsPreparedAsAReview() {
        prior = Optional.of(new PriorRun("TEST-prior-commit", "TEST-summary-ref", List.of()));

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Preparing("TEST-harness"), decision);
        ActionCommand.PrepareHarnessReview prepare = onlyPrepare();
        assertEquals(ReviewRunBinding.Kind.REVIEW, prepare.kind());
        assertNull(prepare.priorRun());
    }

    /** A round that cannot be read is refused, never guessed (review of PR #368, code-quality/M-1). */
    @Test
    void anUnreadableRoundIsRefusedAndNeverPrepared() {
        round = ReviewRuns.UNKNOWN_RUN;

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "review_round_unknown"), decision);
        assertNothingSent();
    }

    /** Call 12: refused with attention, never left "reviewing" by a note-only skip. */
    @Test
    void aMissingReviewerCredentialIsRefused() {
        reviewerCredential = Optional.empty();

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "reviewer_credential_unavailable"), decision);
        assertNothingSent();
    }
}
