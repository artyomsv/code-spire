package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.IntegrationEvent.HarnessReviewPrepared;
import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.orchestrator.llm.CallRefs;
import dev.codespire.orchestrator.llm.ReviewRuns;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static dev.codespire.orchestrator.review.run.HarnessLaunchFixture.CURRENT_RUN;
import static dev.codespire.orchestrator.review.run.HarnessLaunchFixture.HEAD;
import static dev.codespire.orchestrator.review.run.HarnessLaunchFixture.INPUTS;
import static dev.codespire.orchestrator.review.run.HarnessLaunchFixture.PR;
import static dev.codespire.orchestrator.review.run.HarnessLaunchFixture.REPOSITORY;
import static dev.codespire.orchestrator.review.run.HarnessLaunchFixture.RE_REVIEW_INPUTS;
import static dev.codespire.orchestrator.review.run.HarnessLaunchFixture.REVIEW_ID;
import static dev.codespire.orchestrator.review.run.HarnessLaunchFixture.prepared;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A prepared harness review is dispatched as a run of its round, carrying the inputs the worker wrote (harness reviews,
 * step 6). Step 5's dispatch cases moved here from {@code HarnessReviewStartTest} with the code they test.
 *
 * <p>A plain unit test over {@link HarnessLaunchFixture}, whose fakes override every method the launch reaches, because
 * an un-overridden one opens a real database from a unit test (CLAUDE.md, saga test fakes).
 */
class HarnessReviewLaunchTest {

    private final HarnessLaunchFixture fixture = new HarnessLaunchFixture();
    private final List<ReviewRunRequest> dispatched = fixture.dispatched;

    @Test
    void aPreparedReviewDispatchesARunOfItsRound() {
        HarnessReviewStart.Decision decision = launch().launch(prepared(ReviewRunBinding.Kind.REVIEW, CURRENT_RUN));

        assertEquals(new HarnessReviewStart.Started("TEST-run-1", "TEST-harness"), decision);
        assertEquals(1, dispatched.size());
        ReviewRunRequest request = dispatched.getFirst();
        assertEquals(REPOSITORY, request.repositoryId());
        assertEquals(new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.REVIEW, PR, HEAD,
                CallRefs.reviewSlot(HEAD, CURRENT_RUN), CURRENT_RUN), request.binding());
        assertEquals(INPUTS, request.inputs(), "the run carries the files the worker prepared");
        assertEquals("TEST-harness", request.choice().harness());
        assertEquals("TEST-model", request.choice().model());
        assertEquals("high", request.choice().effort());
        assertTrue(request.choice().subscription(), "SUBSCRIPTION pays with a seat");
        assertTrue(request.prompt().contains("/inputs/context.md"), "the agent is told to read its context first");
    }

    @Test
    void aPreparedReReviewDispatchesAReconcileRun() {
        HarnessReviewStart.Decision decision = launch().launch(new HarnessReviewPrepared(REVIEW_ID, PR, HEAD,
                CURRENT_RUN, ReviewRunBinding.Kind.RECONCILE, RE_REVIEW_INPUTS));

        assertEquals(new HarnessReviewStart.Started("TEST-run-1", "TEST-harness"), decision);
        ReviewRunRequest request = dispatched.getFirst();
        assertEquals(new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.RECONCILE, PR, HEAD,
                CallRefs.reviewSlot(HEAD, CURRENT_RUN), CURRENT_RUN), request.binding());
        assertEquals(RE_REVIEW_INPUTS, request.inputs());
        assertTrue(request.prompt().contains("/inputs/prior-findings.md"), request.prompt());
        assertTrue(request.prompt().contains("\"verdicts\""), "a re-review asks for a verdict per prior finding");
    }

    /** A forced re-run ended round 1 while the worker prepared it: nothing is dispatched and nothing refused. */
    @Test
    void aPreparedReviewOfAnEndedRoundIsDropped() {
        HarnessReviewStart.Decision decision = launch().launch(prepared(ReviewRunBinding.Kind.REVIEW, 1));

        assertInstanceOf(HarnessReviewStart.Dropped.class, decision);
        assertTrue(dispatched.isEmpty(), "an ended round's run is never dispatched: " + dispatched);
    }

    @Test
    void anUnreadableRoundIsRefused() {
        fixture.round = ReviewRuns.UNKNOWN_RUN;

        HarnessReviewStart.Decision decision = launch().launch(prepared(ReviewRunBinding.Kind.REVIEW, CURRENT_RUN));

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "review_round_unknown"), decision);
        assertTrue(dispatched.isEmpty());
    }

    /** Call 15: a repository switched off its harness between prepare and launch is refused, never sent to the API. */
    @Test
    void aRemovedHarnessIsRefused() {
        fixture.harness = Optional.empty();

        HarnessReviewStart.Decision decision = launch().launch(prepared(ReviewRunBinding.Kind.REVIEW, CURRENT_RUN));

        assertEquals(new HarnessReviewStart.Refused("no harness", "review_harness_removed"), decision);
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void aRefusedDispatchIsRefusedWithItsReason() {
        fixture.dispatchOutcome = new ReviewRunDispatch.Refused("subscription_unavailable");

        HarnessReviewStart.Decision decision = launch().launch(prepared(ReviewRunBinding.Kind.REVIEW, CURRENT_RUN));

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "subscription_unavailable"), decision);
    }

    /**
     * A forced re-run between the round check and admission cancels the admission; refusing it would refuse round N+1
     * on the same commit (plan check, item 2).
     */
    @Test
    void aCancelledAdmissionDropsTheLaunchAndRefusesNothing() {
        fixture.dispatchOutcome = new ReviewRunDispatch.Refused("review_run_cancelled");

        HarnessReviewStart.Decision decision = launch().launch(prepared(ReviewRunBinding.Kind.REVIEW, CURRENT_RUN));

        assertInstanceOf(HarnessReviewStart.Dropped.class, decision);
    }

    /** An uncertain launch has its queued row; a lost one surfaces as REVIEW_STUCK, so it is not refused here. */
    @Test
    void anUncertainLaunchCountsAsStarted() {
        fixture.dispatchOutcome = new ReviewRunDispatch.Uncertain("TEST-run-1");

        HarnessReviewStart.Decision decision = launch().launch(prepared(ReviewRunBinding.Kind.REVIEW, CURRENT_RUN));

        assertEquals(new HarnessReviewStart.Started("TEST-run-1", "TEST-harness"), decision);
    }

    /** A failure composing the request refuses the review with attention; thrown, it left the review in "reviewing". */
    @Test
    void aRequestThatCannotBeComposedIsRefusedNotLeftReviewing() {
        fixture.promptFailure = new IllegalStateException("TEST-failure");

        HarnessReviewStart.Decision decision = launch().launch(prepared(ReviewRunBinding.Kind.REVIEW, CURRENT_RUN));

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "harness_review_start_failed"), decision);
        assertTrue(dispatched.isEmpty(), "nothing was sent");
    }

    /**
     * A dispatch that throws may already have published the command, so the review is not refused: a terminal refusal
     * would drop the paid run's answer (review of PR #368, code-quality/M-2).
     */
    @Test
    void aDispatchThatThrowsIsNotRefused() {
        IllegalStateException failure = new IllegalStateException("TEST-uncertain-status-write");
        fixture.dispatchFailure = failure;

        HarnessReviewLaunch launch = launch();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> launch.launch(prepared(ReviewRunBinding.Kind.REVIEW, CURRENT_RUN)));

        assertSame(failure, thrown);
    }

    /** No customized prompt resolves to null, and the run still gets the built-in prompt (live on spire-test #93). */
    @Test
    void aRepositoryWithoutACustomPromptIsSentTheDefaultReviewPrompt() {
        launch().launch(prepared(ReviewRunBinding.Kind.REVIEW, CURRENT_RUN));

        String prompt = dispatched.getFirst().prompt();
        assertTrue(prompt.startsWith(PromptCatalog.defaultTemplate(PromptKind.REVIEW).system()),
                "the default review system text opens the prompt: " + prompt);
    }

    @Test
    void aCustomizedPromptOpensTheRunsPrompt() {
        fixture.customReviewPrompt = new PromptTemplate(PromptKind.REVIEW, "TEST-custom-system", "TEST-body");

        launch().launch(prepared(ReviewRunBinding.Kind.REVIEW, CURRENT_RUN));

        assertTrue(dispatched.getFirst().prompt().startsWith("TEST-custom-system"));
    }

    /** Null for RECONCILE too means the default, as step 5 fixed for REVIEW. */
    @Test
    void aReReviewWithoutACustomPromptGetsTheDefaultReconcileText() {
        launch().launch(new HarnessReviewPrepared(REVIEW_ID, PR, HEAD, CURRENT_RUN, ReviewRunBinding.Kind.RECONCILE,
                RE_REVIEW_INPUTS));

        String prompt = dispatched.getFirst().prompt();
        assertTrue(prompt.startsWith(PromptCatalog.defaultTemplate(PromptKind.REVIEW).system()), prompt);
        assertTrue(prompt.contains(PromptCatalog.defaultTemplate(PromptKind.RECONCILE).system()), prompt);
    }

    /** The run id is deterministic, so a redelivered prepare answers AlreadyQueued for the run already sent. */
    @Test
    void aRedeliveredPreparedReviewSendsNoSecondRun() {
        fixture.deterministicIds = true;
        HarnessReviewLaunch launch = launch();
        HarnessReviewPrepared prepared = prepared(ReviewRunBinding.Kind.REVIEW, CURRENT_RUN);

        HarnessReviewStart.Started first = assertInstanceOf(HarnessReviewStart.Started.class, launch.launch(prepared));
        HarnessReviewStart.Started second = assertInstanceOf(HarnessReviewStart.Started.class, launch.launch(prepared));

        assertEquals(first.runId(), second.runId(), "the same review call is the same run");
        assertEquals(dispatched.getFirst().binding(), dispatched.getLast().binding());
    }

    private HarnessReviewLaunch launch() {
        return fixture.launch();
    }
}
