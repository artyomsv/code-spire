package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.orchestrator.repository.StartReview;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a review starts: at once, as before, or as a wait for the head's checks (V121). */
class ReviewStartGateTest extends ChecksWaitFixture {

    /** "Immediately" behaves exactly as today: the run's own first command, unchanged, and no wait. */
    @Test
    void immediatelySendsTheRunsFirstCommandUnchanged() {
        setting = StartReview.IMMEDIATELY;
        ActionCommand.FetchDiff fetch = fetch(HEAD);
        gate().begin(fetch);
        assertEquals(List.of(fetch), emitted);
        assertNull(waits.waitOf(REVIEW_ID));
        assertTrue(statuses.isEmpty(), statuses.toString());
    }

    /** A repository switched back to "immediately" drops the wait an earlier head left. */
    @Test
    void immediatelyDropsAnEarlierWait() {
        waits.startWaiting(waitOn(HEAD, 0));
        setting = StartReview.IMMEDIATELY;
        gate().begin(fetch(NEXT_HEAD));
        assertNull(waits.waitOf(REVIEW_ID));
        assertEquals(1, fetches().size());
    }

    @Test
    void afterChecksRecordsAWaitAndSendsNothing() {
        gate().begin(fetch(HEAD));
        assertTrue(emitted.isEmpty(), "nothing runs and nothing is charged while the review waits");
        ChecksWait wait = waits.waitOf(REVIEW_ID);
        assertEquals(HEAD, wait.head());
        assertTrue(wait.untrusted(), "the start sends the fork flag the event carried");
        assertEquals(List.of(ChecksWaitStatus.WAITING), statuses);
        assertEquals(List.of("ChecksWaitStarted"), history);
    }

    /** A forge with no check read (GitLab, Bitbucket) reviews at once whatever the setting says. */
    @Test
    void aForgeWithoutACheckReadStartsAtOnce() {
        checksReadable = false;
        gate().begin(fetch(HEAD));
        assertEquals(1, fetches().size());
        assertFalse(waits.isWaiting(REVIEW_ID));
    }

    /** A new commit while waiting replaces the wait for the old head (the aggregate superseded it). */
    @Test
    void aNewCommitReplacesTheWait() {
        gate().begin(fetch(HEAD));
        gate().begin(fetch(NEXT_HEAD));
        assertEquals(NEXT_HEAD, waits.waitOf(REVIEW_ID).head());
        assertTrue(emitted.isEmpty());
    }
}
