package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.IntegrationEvent.ChecksChanged;
import dev.codespire.orchestrator.repository.StartReview;
import dev.codespire.orchestrator.review.checks.ChecksWait.Stage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admission and start (V121): the run is accepted, recorded with the round the aggregate accepted and its label fence
 * closed, all in the round lock; begin then acts on that run only.
 */
class ReviewStartGateTest extends ChecksWaitFixture {

    /** "Immediately" sends the run's own first command, unchanged; the run is recorded as started. */
    @Test
    void immediatelySendsTheRunsFirstCommandUnchanged() {
        setting = StartReview.IMMEDIATELY;
        ActionCommand.FetchDiff fetch = fetch(NEXT_HEAD);
        gate().begin(fetch, admitRun(NEXT_HEAD));
        assertEquals(List.of(fetch), emitted);
        assertEquals(Stage.STARTED, waits.rowOf(REVIEW_ID).stage());
        assertTrue(statuses.isEmpty(), statuses.toString());
    }

    /** A run admitted to start at once replaces an earlier run's wait or hold. */
    @Test
    void immediatelyReplacesAnEarlierRunsWait() {
        waits.startWaiting(waitOn(HEAD, 0));
        setting = StartReview.IMMEDIATELY;
        requestRun(NEXT_HEAD);
        assertEquals(Stage.STARTED, waits.rowOf(REVIEW_ID).stage());
        assertEquals(round(), waits.rowOf(REVIEW_ID).round());
        assertEquals(1, fetches().size());
    }

    @Test
    void afterChecksRecordsTheAcceptedRoundsWaitAndSendsNothing() {
        ReviewStartGate.AcceptedRun run = admitRun(NEXT_HEAD);
        assertEquals(round(), run.round(), "the round the aggregate accepted, folded from its own append");
        assertEquals(List.of("supersede"), receiptLog, "the earlier runs' label receipts are closed at admission");
        gate().begin(fetch(NEXT_HEAD), run);
        assertTrue(emitted.isEmpty(), "nothing runs and nothing is charged while the review waits");
        ChecksWait wait = waits.rowOf(REVIEW_ID);
        assertEquals(NEXT_HEAD, wait.head());
        assertEquals(run.round(), wait.round());
        assertTrue(wait.untrusted(), "the start sends the fork flag the event carried");
        assertEquals(List.of(run.round()), waits.shownWaiting);
        assertEquals(List.of("ChecksWaitStarted"), history);
    }

    /** A forge with no check read (GitLab, Bitbucket) reviews at once whatever the setting says. */
    @Test
    void aForgeWithoutACheckReadStartsAtOnce() {
        checksReadable = false;
        requestRun(NEXT_HEAD);
        assertEquals(1, fetches().size());
        assertFalse(waits.isWaiting(REVIEW_ID));
    }

    /**
     * Same head, two re-runs: A is admitted (round 2) and pauses before begin; B is admitted (round 3), shown waiting,
     * reads green and starts, completing its row. A then begins: its round no longer waits, so it shows nothing, sends
     * nothing and leaves B's record and status alone.
     */
    @Test
    void aPausedRerunBeginsNothingAfterTheNewerRunStarted() {
        ReviewStartGate.AcceptedRun first = admitRun(HEAD);
        requestRun(HEAD);
        verdict = green();
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, HEAD));
        gate().begin(fetch(HEAD), first);
        assertEquals(1, fetches().size());
        assertEquals(Stage.STARTED, waits.rowOf(REVIEW_ID).stage());
        assertEquals(first.round() + 1, waits.rowOf(REVIEW_ID).round());
        assertEquals(List.of(first.round() + 1), waits.shownWaiting, "the paused run never showed waiting");
        assertEquals(List.of("reviewing"), statuses);
    }

    /** A later run's record is never replaced by an earlier round, whatever order the writes arrive in. */
    @Test
    void anEarlierRunsLateRegistrationKeepsTheLaterRow() {
        admitRun(NEXT_HEAD);
        ChecksWait later = waits.rowOf(REVIEW_ID);
        waits.startWaiting(new ChecksWait(REVIEW_ID, REPOSITORY_ID, HEAD, later.round() - 1, true, later.waitingSince(),
                Stage.WAITING, null));
        assertEquals(NEXT_HEAD, waits.rowOf(REVIEW_ID).head());
    }

    /**
     * The admission fails after the aggregate accepted the run (closing the label receipts failed), so its event is
     * redelivered; the redelivery finds the run accepted and unrecorded, records it and shows it waiting.
     */
    @Test
    void aFailedAdmissionIsRecordedByItsRedelivery() {
        admissionFailure = new IllegalStateException("TEST-database-unavailable");
        assertThrows(IllegalStateException.class, () -> admitRun(NEXT_HEAD));
        assertEquals(0, waits.admittedRound(REVIEW_ID), "nothing recorded for the accepted round");
        ReviewStartGate.Admission redelivered = new ReviewStartGate.Admission(REVIEW_ID,
                new RecordCommand.RequestReview(NEXT_HEAD, "UPDATED", false), true);
        assertTrue(gate().admit(redelivered).isEmpty(), "the aggregate already accepted it");
        assertEquals(round(), waits.rowOf(REVIEW_ID).round());
        assertEquals(List.of(round()), waits.shownWaiting);
        assertTrue(gate().admit(redelivered).isEmpty());
        assertEquals(List.of(round()), waits.shownWaiting, "a recorded run is not recorded again");
    }
}
