package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.ChecksChanged;
import dev.codespire.orchestrator.repository.StartReview;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a review starts: at once, as before, or as a wait of the admitted run for the head's checks (V121). */
class ReviewStartGateTest extends ChecksWaitFixture {

    /** "Immediately" behaves exactly as today: the run's own first command, unchanged, and no wait. */
    @Test
    void immediatelySendsTheRunsFirstCommandUnchanged() {
        setting = StartReview.IMMEDIATELY;
        ActionCommand.FetchDiff fetch = fetch(HEAD);
        gate().begin(fetch);
        assertEquals(List.of(fetch), emitted);
        assertNull(waits.rowOf(REVIEW_ID));
        assertTrue(statuses.isEmpty(), statuses.toString());
    }

    /** A repository switched back to "immediately" drops the wait an earlier run left. */
    @Test
    void immediatelyDropsAnEarlierRunsWait() {
        waits.startWaiting(waitOn(HEAD, 0));
        setting = StartReview.IMMEDIATELY;
        admitRun(NEXT_HEAD);
        gate().begin(fetch(NEXT_HEAD));
        assertNull(waits.rowOf(REVIEW_ID));
        assertEquals(1, fetches().size());
    }

    @Test
    void afterChecksRecordsAWaitOfTheAdmittedRunAndSendsNothing() {
        gate().begin(fetch(HEAD));
        assertTrue(emitted.isEmpty(), "nothing runs and nothing is charged while the review waits");
        ChecksWait wait = waits.rowOf(REVIEW_ID);
        assertEquals(HEAD, wait.head());
        assertEquals(round(), wait.round());
        assertTrue(wait.untrusted(), "the start sends the fork flag the event carried");
        assertEquals(List.of(ChecksWaitStatus.WAITING), statuses);
        assertEquals(List.of("ChecksWaitStarted"), history);
        assertEquals(List.of("supersede"), receiptLog, "every admission closes the earlier runs' label receipts");
    }

    /** A forge with no check read (GitLab, Bitbucket) reviews at once whatever the setting says. */
    @Test
    void aForgeWithoutACheckReadStartsAtOnce() {
        checksReadable = false;
        gate().begin(fetch(HEAD));
        assertEquals(1, fetches().size());
        assertFalse(waits.isWaiting(REVIEW_ID));
    }

    /**
     * A run that cannot be identified keeps the gate closed: nothing is sent, the review shows waiting, and the sweep
     * admits it again once the aggregate answers. CI still decides the start.
     */
    @Test
    void anUnreadableRunKeepsTheGateClosedUntilTheSweepAdmitsIt() {
        failingStateReads = 1;
        gate().begin(fetch(HEAD));
        assertTrue(emitted.isEmpty(), "never an immediate start on a failed read");
        assertNull(waits.rowOf(REVIEW_ID));
        assertEquals(List.of(ChecksWaitStatus.WAITING), statuses);
        assertEquals(List.of("ChecksWaitPending"), history);
        waits.pending.add(new ChecksWaitStore.PendingAdmission(REVIEW_ID, REPOSITORY_ID, HEAD, true));
        sweep().sweep();
        assertEquals(round(), waits.rowOf(REVIEW_ID).round());
        assertTrue(emitted.isEmpty(), "pending checks still hold the review");
        verdict = green();
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, HEAD));
        assertEquals(1, fetches().size());
    }

    /** A new commit while waiting replaces the wait for the old head (the aggregate superseded it). */
    @Test
    void aNewCommitReplacesTheWait() {
        gate().begin(fetch(HEAD));
        requestRun(NEXT_HEAD);
        assertEquals(NEXT_HEAD, waits.rowOf(REVIEW_ID).head());
        assertEquals(2, waits.rowOf(REVIEW_ID).round());
        assertTrue(emitted.isEmpty());
    }

    /**
     * A re-run of HEAD is accepted and pauses before registering; a push of NEXT_HEAD is accepted and registers. The
     * paused re-run then registers nothing: its run is not the review's any more. NEXT_HEAD's wait survives and starts.
     */
    @Test
    void aPausedRerunNeverOverwritesALaterPushsWait() {
        gate().begin(fetch(HEAD));
        admitRun(HEAD);
        requestRun(NEXT_HEAD);
        gate().begin(fetch(HEAD));
        ChecksWait wait = waits.rowOf(REVIEW_ID);
        assertEquals(NEXT_HEAD, wait.head());
        assertEquals(3, wait.round());
        verdict = green();
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, NEXT_HEAD));
        assertEquals(List.of(NEXT_HEAD), fetches().stream().map(ActionCommand.FetchDiff::commit).toList());
    }

    /** A registration that arrives after a later run's never replaces it, whatever the order of the writes. */
    @Test
    void anEarlierRunsLateRegistrationKeepsTheLaterRow() {
        waits.startWaiting(waitOn(NEXT_HEAD, 0).at(ChecksWait.Stage.WAITING));
        ChecksWait later = waits.rowOf(REVIEW_ID);
        waits.startWaiting(new ChecksWait(REVIEW_ID, REPOSITORY_ID, HEAD, later.round() - 1, true, later.waitingSince(),
                ChecksWait.Stage.WAITING, null));
        assertEquals(NEXT_HEAD, waits.rowOf(REVIEW_ID).head());
    }
}
