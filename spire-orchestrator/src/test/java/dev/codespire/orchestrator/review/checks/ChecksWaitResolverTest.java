package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.IntegrationEvent.ChecksChanged;
import dev.codespire.contract.lifecycle.ReviewState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a wait for checks ends (docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md, Tests): a webhook or
 * the sweep reads the head, green starts the review, red skips it, the timeout starts one that never approves. These
 * run each resolution to completion; {@link ChecksWaitRaceTest} interleaves them.
 */
class ChecksWaitResolverTest extends ChecksWaitFixture {

    private void waiting() {
        gate().begin(fetch(HEAD));
        statuses.clear();
        history.clear();
    }

    private void wake(String head) {
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, head));
    }

    @Test
    void aWebhookWakeUpStartsTheReviewOnGreenWithoutTheSweep() {
        waiting();
        verdict = green();
        wake(HEAD);
        ActionCommand.FetchDiff started = fetches().getFirst();
        assertEquals(HEAD, started.commit());
        assertEquals("TEST-packed-at-start", started.scmCredential(), "the credential is packed when the review starts");
        assertTrue(started.untrustedPullRequest());
        assertEquals(List.of("reviewing"), statuses);
        assertNull(waits.rowOf(REVIEW_ID), "a completed start leaves no row");
    }

    @Test
    void theSweepStartsItWhenNoWebhookArrives() {
        waiting();
        verdict = green();
        sweep().sweep();
        assertEquals(1, fetches().size());
    }

    /** Sequential duplicates: a second wake-up after the first finished finds no wait to act on. */
    @Test
    void aRepeatedWakeUpStartsOneReview() {
        waiting();
        verdict = green();
        ChecksWait wait = waits.rowOf(REVIEW_ID);
        resolver().resolve(wait);
        resolver().resolve(wait);
        wake(HEAD);
        assertEquals(1, fetches().size());
    }

    @Test
    void aHeadWithNoChecksStartsAfterTheGraceRead() {
        verdict = new ChecksVerdict(ChecksVerdict.Kind.NO_CHECKS, List.of());
        waits.startWaiting(waitOn(HEAD, 5));
        resolver().resolve(waits.rowOf(REVIEW_ID));
        assertTrue(emitted.isEmpty(), "checks may still register within the grace period");
        waits.startWaiting(waitOn(HEAD, ChecksWaitDecision.GRACE.toSeconds()));
        resolver().resolve(waits.rowOf(REVIEW_ID));
        assertEquals(1, fetches().size());
    }

    @Test
    void redSkipsTheReviewChargesNothingAndLabelsItsRun() {
        verdictLabels = true;
        waiting();
        verdict = red("TEST-build", "TEST-lint");
        wake(HEAD);
        assertTrue(fetches().isEmpty(), "no review runs, so nothing is charged");
        assertEquals(new RecordCommand.SkipReview(HEAD, ChecksWaitStatus.CHECKS_FAILED), recorded.getLast());
        assertEquals(ReviewState.Status.CANCELLED, state.status());
        ActionCommand.LabelChecksFailed label = labels().getFirst();
        assertEquals(HEAD, label.commit());
        assertEquals(round, label.round());
        assertTrue(notes.getLast().contains("TEST-build, TEST-lint"), notes.toString());
        assertEquals(List.of("ChecksFailed"), history);
        assertNull(waits.rowOf(REVIEW_ID));
    }

    @Test
    void redWithVerdictLabelsOffSetsNoLabel() {
        waiting();
        verdict = red("TEST-build");
        wake(HEAD);
        assertTrue(emitted.isEmpty(), emitted.toString());
        assertEquals(ReviewState.Status.CANCELLED, state.status());
    }

    /** After a red skip, a new commit is a new run that waits for its own checks. */
    @Test
    void aNewCommitAfterRedWaitsAgain() {
        waiting();
        verdict = red("TEST-build");
        wake(HEAD);
        requestRun(NEXT_HEAD);
        assertEquals(NEXT_HEAD, waits.rowOf(REVIEW_ID).head());
        assertTrue(fetches().isEmpty());
    }

    /** A wake-up for the superseded head finds no wait on it; the new head still waits. */
    @Test
    void aSupersededHeadsWakeUpDoesNothing() {
        waiting();
        requestRun(NEXT_HEAD);
        verdict = green();
        wake(HEAD);
        assertTrue(emitted.isEmpty());
        assertTrue(reads.isEmpty(), "no read for a head nothing waits on");
        assertEquals(NEXT_HEAD, waits.rowOf(REVIEW_ID).head());
    }

    /** A closed pull request ends the wait: the aggregate cancelled the run, so the next read drops it unread. */
    @Test
    void aCloseEndsTheWait() {
        waiting();
        state = new ReviewState(REVIEW_ID, REPO, 7L, ReviewState.Status.CANCELLED, HEAD, Set.of(), null, Map.of(),
                Set.of());
        verdict = green();
        wake(HEAD);
        assertTrue(emitted.isEmpty());
        assertTrue(reads.isEmpty());
        assertFalse(waits.isWaiting(REVIEW_ID));
    }

    @Test
    void pendingChecksAtTheTimeoutStartAReviewThatMayNotApprove() {
        waits.startWaiting(waitOn(HEAD, ChecksWaitDecision.TIMEOUT.toSeconds()));
        resolver().resolve(waits.rowOf(REVIEW_ID));
        assertEquals(1, fetches().size());
        assertTrue(waits.startedUnfinished(REVIEW_ID, HEAD), "the kept row holds the verdict below Approve");
        assertEquals(List.of("reviewing"), statuses);
        assertEquals(List.of("ChecksTimedOut"), history);
    }

    @Test
    void pendingChecksBeforeTheTimeoutKeepWaiting() {
        waiting();
        wake(HEAD);
        assertTrue(emitted.isEmpty());
        assertTrue(waits.isWaiting(REVIEW_ID));
    }

    @Test
    void anEventForAHeadWithNoWaitingReviewDoesNothing() {
        wake(HEAD);
        resolver().wake(UUID.randomUUID(), new ChecksChanged(REPO, HEAD));
        assertTrue(emitted.isEmpty());
        assertTrue(reads.isEmpty());
    }
}
