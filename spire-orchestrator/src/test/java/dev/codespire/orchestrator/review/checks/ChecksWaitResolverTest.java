package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.IntegrationEvent.ChecksChanged;
import dev.codespire.contract.lifecycle.ReviewState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a wait for checks ends (docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md, Tests): a webhook or
 * the sweep reads the head, green starts the review, red skips it, the timeout starts one that never approves.
 */
class ChecksWaitResolverTest extends ChecksWaitFixture {

    private void waiting() {
        gate().begin(fetch(HEAD));
        statuses.clear();
        history.clear();
    }

    @Test
    void aWebhookWakeUpStartsTheReviewOnGreenWithoutTheSweep() {
        waiting();
        verdict = green();
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, HEAD));
        ActionCommand.FetchDiff started = fetches().getFirst();
        assertEquals(HEAD, started.commit());
        assertEquals("TEST-packed-at-start", started.scmCredential(), "the credential is packed when the review starts");
        assertTrue(started.untrustedPullRequest());
        assertEquals(List.of("reviewing"), statuses);
        assertNull(waits.waitOf(REVIEW_ID));
    }

    @Test
    void theSweepStartsItWhenNoWebhookArrives() {
        waiting();
        verdict = green();
        waits.due.add(waits.waitOf(REVIEW_ID));
        sweep().sweep();
        assertEquals(1, fetches().size());
    }

    /** A duplicate wake-up, or a sweep racing the webhook, starts one review: only one caller takes the wait. */
    @Test
    void aDuplicateWakeUpStartsOneReview() {
        waiting();
        verdict = green();
        ChecksWait wait = waits.waitOf(REVIEW_ID);
        resolver().resolve(wait);
        resolver().resolve(wait);
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, HEAD));
        assertEquals(1, fetches().size());
    }

    @Test
    void aHeadWithNoChecksStartsAfterTheGraceRead() {
        verdict = new ChecksVerdict(ChecksVerdict.Kind.NO_CHECKS, List.of());
        waits.startWaiting(waitOn(HEAD, 5));
        resolver().resolve(waits.waitOf(REVIEW_ID));
        assertTrue(emitted.isEmpty(), "checks may still register within the grace period");
        waits.startWaiting(waitOn(HEAD, ChecksWaitDecision.GRACE.toSeconds()));
        resolver().resolve(waits.waitOf(REVIEW_ID));
        assertEquals(1, fetches().size());
    }

    @Test
    void redSkipsTheReviewChargesNothingAndLabels() {
        verdictLabels = true;
        waiting();
        verdict = red("TEST-build", "TEST-lint");
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, HEAD));
        assertTrue(fetches().isEmpty(), "no review runs, so nothing is charged");
        assertEquals(new RecordCommand.SkipReview(HEAD, ChecksWaitStatus.CHECKS_FAILED), recorded.getLast());
        assertEquals(ReviewState.Status.CANCELLED, state.status());
        var label = assertInstanceOf(ActionCommand.LabelChecksFailed.class, emitted.getLast());
        assertEquals(HEAD, label.commit());
        assertTrue(note.contains("TEST-build, TEST-lint"), note);
        assertEquals(List.of("ChecksFailed"), history);
    }

    @Test
    void redWithVerdictLabelsOffSetsNoLabel() {
        waiting();
        verdict = red("TEST-build");
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, HEAD));
        assertTrue(emitted.isEmpty(), emitted.toString());
        assertEquals(ReviewState.Status.CANCELLED, state.status());
    }

    /** A duplicate red wake-up labels once: the second finds the run already ended and the wait gone. */
    @Test
    void aDuplicateRedWakeUpLabelsOnce() {
        verdictLabels = true;
        waiting();
        verdict = red("TEST-build");
        ChecksWait wait = waits.waitOf(REVIEW_ID);
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, HEAD));
        resolver().resolve(wait);
        assertEquals(1, emitted.size());
    }

    /** After a red skip, a new commit is a new run that waits for its own checks. */
    @Test
    void aNewCommitAfterRedWaitsAgain() {
        waiting();
        verdict = red("TEST-build");
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, HEAD));
        state = reviewing(NEXT_HEAD);
        gate().begin(fetch(NEXT_HEAD));
        assertEquals(NEXT_HEAD, waits.waitOf(REVIEW_ID).head());
        assertTrue(fetches().isEmpty());
    }

    /** A wake-up for the superseded head finds no wait on it; the new head still waits. */
    @Test
    void aSupersededHeadsWakeUpDoesNothing() {
        waiting();
        state = reviewing(NEXT_HEAD);
        gate().begin(fetch(NEXT_HEAD));
        verdict = green();
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, HEAD));
        assertTrue(emitted.isEmpty());
        assertTrue(reads.isEmpty(), "no read for a head nothing waits on");
        assertEquals(NEXT_HEAD, waits.waitOf(REVIEW_ID).head());
    }

    /** A closed pull request ends the wait: the aggregate cancelled the run, so the next read drops it unread. */
    @Test
    void aCloseEndsTheWait() {
        waiting();
        state = new ReviewState(REVIEW_ID, REPO, 7L, ReviewState.Status.CANCELLED, HEAD, java.util.Set.of(), null,
                java.util.Map.of(), java.util.Set.of());
        verdict = green();
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, HEAD));
        assertTrue(emitted.isEmpty());
        assertTrue(reads.isEmpty());
        assertFalse(waits.isWaiting(REVIEW_ID));
    }

    /** A skip decided for a head a newer commit replaced in the meantime never ends the newer run. */
    @Test
    void aStaleSkipNeverCancelsTheNewerRun() {
        waits.startWaiting(waitOn(HEAD, 0));
        state = reviewing(NEXT_HEAD);
        verdict = red("TEST-build");
        // Straight to the outcome, past the resolver's own aggregate check, to prove the guard in the skip itself.
        resolver().outcomes.skip(waitOn(HEAD, 0), List.of("TEST-build"));
        assertEquals(ReviewState.Status.REVIEWING, state.status());
        assertNull(note);
    }

    @Test
    void pendingChecksAtTheTimeoutStartAReviewThatMayNotApprove() {
        verdict = ChecksVerdict.PENDING;
        waits.startWaiting(waitOn(HEAD, ChecksWaitDecision.TIMEOUT.toSeconds()));
        resolver().resolve(waits.waitOf(REVIEW_ID));
        assertEquals(1, fetches().size());
        assertTrue(waits.startedUnfinished(REVIEW_ID, HEAD), "the kept row holds the verdict below Approve");
        assertEquals(List.of("reviewing"), statuses);
        assertEquals(List.of("ChecksTimedOut"), history);
    }

    @Test
    void pendingChecksBeforeTheTimeoutKeepWaiting() {
        waiting();
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, HEAD));
        assertTrue(emitted.isEmpty());
        assertTrue(waits.isWaiting(REVIEW_ID));
    }

    @Test
    void anEventForAHeadWithNoWaitingReviewDoesNothing() {
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, HEAD));
        resolver().wake(UUID.randomUUID(), new ChecksChanged(REPO, HEAD));
        assertTrue(emitted.isEmpty());
        assertTrue(reads.isEmpty());
    }
}
