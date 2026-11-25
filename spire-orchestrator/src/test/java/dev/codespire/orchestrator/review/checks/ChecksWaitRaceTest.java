package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.event.IntegrationEvent.ChecksChanged;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.orchestrator.review.checks.ChecksWait.Stage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interleavings of two resolvers, a push or a re-run, and a crash between a claim and its completion. Each test places
 * the second actor inside the first one's read or send ({@link ChecksWaitFixture#duringRead},
 * {@link ChecksWaitFixture#duringSend}), so the first resolver has already passed its own checks when the second acts.
 */
class ChecksWaitRaceTest extends ChecksWaitFixture {

    private static final String LOST_ACK = "TEST-lost-broker-ack";

    private ChecksWait waiting(long ageSeconds) {
        waits.startWaiting(waitOn(HEAD, ageSeconds));
        return waits.rowOf(REVIEW_ID);
    }

    private static Runnable lostAck() {
        return () -> {
            throw new IllegalStateException(LOST_ACK);
        };
    }

    /** Two red readers: one claims the skip; the run is ended, noted and labelled once. */
    @Test
    void twoRedReadersSkipOnce() {
        verdictLabels = true;
        ChecksWait wait = waiting(0);
        verdict = red("TEST-build");
        duringRead = () -> resolver().resolve(wait);
        resolver().resolve(wait);
        assertEquals(1, skips());
        assertEquals(1, notes.size());
        assertEquals(1, labels().size());
        assertEquals(Stage.SKIPPED, waits.rowOf(REVIEW_ID).stage());
    }

    /** The timeout start wins; the red reader that read first never cancels the started run. */
    @Test
    void aRedReaderLosingToTheTimeoutCancelsNothing() {
        ChecksWait wait = waiting(ChecksWaitDecision.TIMEOUT.toSeconds());
        verdict = red("TEST-build");
        duringRead = () -> {
            verdict = ChecksVerdict.PENDING;
            resolver().resolve(wait);
            verdict = red("TEST-build");
        };
        resolver().resolve(wait);
        assertEquals(1, fetches().size());
        assertEquals(0, skips());
        assertEquals(ReviewState.Status.REVIEWING, state.status());
        assertTrue(waits.startedUnfinished(new ChecksWait.Run(REVIEW_ID, HEAD, round())));
    }

    /** The red skip wins; the timeout reader that read first starts nothing. */
    @Test
    void aTimeoutReaderLosingToRedStartsNothing() {
        ChecksWait wait = waiting(ChecksWaitDecision.TIMEOUT.toSeconds());
        duringRead = () -> {
            verdict = red("TEST-build");
            resolver().resolve(wait);
            verdict = ChecksVerdict.PENDING;
        };
        resolver().resolve(wait);
        assertTrue(fetches().isEmpty());
        assertEquals(1, skips());
        assertEquals(ReviewState.Status.CANCELLED, state.status());
    }

    /** A re-run on the same head while a red read is out: the read's run is no longer the wait's, so nothing acts. */
    @Test
    void aRerunOnTheSameHeadDuringARedReadIsNotCancelled() {
        ChecksWait wait = waiting(0);
        verdict = red("TEST-build");
        duringRead = () -> requestRun(HEAD);
        resolver().resolve(wait);
        assertEquals(0, skips());
        assertEquals(ReviewState.Status.REVIEWING, state.status());
        assertEquals(2, waits.rowOf(REVIEW_ID).round());
        assertEquals(Stage.WAITING, waits.rowOf(REVIEW_ID).stage());
    }

    /** A claimed skip finished after a re-run on the same head took the review: the newer run is never cancelled. */
    @Test
    void aClaimedSkipNeverCancelsAReRunOfTheSameHead() {
        ChecksWait claimed = waiting(0).skipping("TEST-build");
        waits.claim(claimed);
        admitRun(HEAD);
        outcomes().finishSkip(claimed);
        assertEquals(0, skips());
        assertEquals(ReviewState.Status.REVIEWING, state.status());
        assertEquals(round(), waits.rowOf(REVIEW_ID).round(), "the re-run's own record is untouched");
        assertEquals(Stage.ADMITTED, waits.rowOf(REVIEW_ID).stage());
    }

    /**
     * A late reader holding a waiting snapshot, while the winning skip is between ending the run and its label, drops
     * nothing: the claim is the winner's recovery intent, and the sweep still finishes the skip after its crash.
     */
    @Test
    void aLateReadersStaleSnapshotNeverDropsTheWinningClaim() {
        verdictLabels = true;
        ChecksWait stale = waiting(0);
        verdict = red("TEST-build");
        duringSend = () -> {
            resolver().resolve(stale);
            throw new IllegalStateException(LOST_ACK);
        };
        assertThrows(IllegalStateException.class, () -> resolver().resolve(stale));
        assertEquals(Stage.SKIPPING, waits.rowOf(REVIEW_ID).stage(), "the stale snapshot did not drop the claim");
        sweep().sweep();
        assertEquals(1, labels().size());
        assertEquals(Stage.SKIPPED, waits.rowOf(REVIEW_ID).stage());
    }

    /** A recovered start of round 1, after a re-run of the same head installed round 2's wait, sends nothing. */
    @Test
    void aRecoveredStartOfAnEarlierRoundSendsNothing() {
        for (Stage stage : List.of(Stage.STARTING, Stage.STARTING_UNFINISHED)) {
            ChecksWait claimed = waiting(ChecksWaitDecision.TIMEOUT.toSeconds()).at(stage);
            waits.claim(claimed);
            requestRun(HEAD);
            resolver().resume(claimed);
            assertTrue(fetches().isEmpty(), stage.name());
            ChecksWait newer = waits.rowOf(REVIEW_ID);
            assertEquals(round(), newer.round(), stage.name());
            assertEquals(Stage.WAITING, newer.stage(), "the re-run's own wait is unchanged");
            assertTrue(!waits.startedUnfinished(new ChecksWait.Run(REVIEW_ID, HEAD, round())), "no earlier run's hold on the new run");
        }
    }

    /** A crash between the claim and the broker ack: the claim survives, and the sweep after a restart starts it. */
    @Test
    void aStartLostBeforeItsAckIsSentByTheSweep() {
        waiting(0);
        verdict = green();
        duringSend = lostAck();
        assertThrows(IllegalStateException.class, () -> resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, HEAD)));
        assertEquals(Stage.STARTING, waits.rowOf(REVIEW_ID).stage());
        assertTrue(fetches().isEmpty());
        sweep().sweep();
        assertEquals(List.of(HEAD), fetches().stream().map(fetch -> fetch.commit()).toList());
        assertEquals(Stage.STARTED, waits.rowOf(REVIEW_ID).stage());
    }

    @Test
    void aTimedOutStartLostBeforeItsAckIsSentByTheSweepAndKeepsItsHold() {
        ChecksWait wait = waiting(ChecksWaitDecision.TIMEOUT.toSeconds());
        duringSend = lostAck();
        assertThrows(IllegalStateException.class, () -> resolver().resolve(wait));
        assertTrue(waits.startedUnfinished(new ChecksWait.Run(REVIEW_ID, HEAD, round())), "the hold stands while the start is pending");
        sweep().sweep();
        assertEquals(1, fetches().size());
        assertEquals(Stage.TIMED_OUT, waits.rowOf(REVIEW_ID).stage());
    }

    /** A crash after the run ended but before its label: the sweep finishes the skip, labelling it once. */
    @Test
    void aSkipLostBeforeItsLabelIsFinishedByTheSweep() {
        verdictLabels = true;
        ChecksWait wait = waiting(0);
        verdict = red("TEST-build");
        duringSend = lostAck();
        assertThrows(IllegalStateException.class, () -> resolver().resolve(wait));
        assertEquals(Stage.SKIPPING, waits.rowOf(REVIEW_ID).stage());
        assertEquals(ReviewState.Status.CANCELLED, state.status());
        sweep().sweep();
        assertEquals(1, labels().size());
        assertTrue(notes.getLast().contains("TEST-build"), "the failed checks survive the restart in the row");
        assertEquals(Stage.SKIPPED, waits.rowOf(REVIEW_ID).stage());
    }

    /** A push lands while a start's send fails: the new head's wait is kept, never overwritten, and still starts. */
    @Test
    void aPushDuringAFailedSendKeepsTheNewHeadsWait() {
        waiting(0);
        verdict = green();
        duringSend = () -> {
            requestRun(NEXT_HEAD);
            throw new IllegalStateException(LOST_ACK);
        };
        assertThrows(IllegalStateException.class, () -> resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, HEAD)));
        assertEquals(NEXT_HEAD, waits.rowOf(REVIEW_ID).head());
        assertEquals(Stage.WAITING, waits.rowOf(REVIEW_ID).stage());
        resolver().wake(REPOSITORY_ID, new ChecksChanged(REPO, NEXT_HEAD));
        assertEquals(List.of(NEXT_HEAD), fetches().stream().map(fetch -> fetch.commit()).toList());
    }
}
