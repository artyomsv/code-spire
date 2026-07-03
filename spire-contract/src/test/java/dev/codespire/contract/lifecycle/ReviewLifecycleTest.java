package dev.codespire.contract.lifecycle;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.DomainEvent.ReviewCancelled;
import dev.codespire.contract.event.DomainEvent.ReviewCompleted;
import dev.codespire.contract.event.DomainEvent.ReviewFailedTerminally;
import dev.codespire.contract.event.DomainEvent.ReviewRequested;
import dev.codespire.contract.event.DomainEvent.ReviewSuperseded;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.command.RecordCommand.CancelReview;
import dev.codespire.contract.command.RecordCommand.RecordCommentsPosted;
import dev.codespire.contract.command.RecordCommand.RecordFailure;
import dev.codespire.contract.command.RecordCommand.RequestReview;
import dev.codespire.contract.lifecycle.ReviewState.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Given/When/Then over the CONTRACT §6 decide table. Pure functions, no infra. */
class ReviewLifecycleTest {

    private final ReviewLifecycle decider = new ReviewLifecycle();

    private ReviewState given(DomainEvent... history) {
        ReviewState state = decider.initialState();
        for (DomainEvent e : history) {
            state = decider.evolve(state, e);
        }
        return state;
    }

    private List<DomainEvent> when(ReviewState state, RecordCommand command) {
        return decider.decide(command, state);
    }

    @Test
    void newCommitStartsReview() {
        var events = when(given(), new RequestReview("abc123", "OPENED", false));
        assertEquals(List.of(new ReviewRequested("abc123", "OPENED")), events);
    }

    @Test
    void sameCommitIsIdempotentNoOp() {
        var state = given(new ReviewRequested("abc123", "OPENED"));
        assertTrue(when(state, new RequestReview("abc123", "UPDATED", false)).isEmpty());
    }

    @Test
    void reviewedCommitIsNoOpWithoutForce() {
        var state = given(new ReviewRequested("abc123", "OPENED"),
                new ReviewCompleted("abc123", "c-1"));
        assertTrue(when(state, new RequestReview("abc123", "UPDATED", false)).isEmpty());
    }

    @Test
    void newerCommitSupersedesInFlightRun() {
        var state = given(new ReviewRequested("abc123", "OPENED"));
        var events = when(state, new RequestReview("def456", "UPDATED", false));
        assertEquals(List.of(new ReviewSuperseded("abc123"),
                new ReviewRequested("def456", "UPDATED")), events);
    }

    @Test
    void forceRestartsTheActiveCommit() {
        // forcing the mid-run commit = supersede-then-restart (CONTRACT §6 note)
        var state = given(new ReviewRequested("abc123", "OPENED"));
        var events = when(state, new RequestReview("abc123", "MANUAL", true));
        assertEquals(List.of(new ReviewSuperseded("abc123"),
                new ReviewRequested("abc123", "MANUAL")), events);
    }

    @Test
    void forceBypassesReviewedCommitIdempotency() {
        var state = given(new ReviewRequested("abc123", "OPENED"),
                new ReviewCompleted("abc123", "c-1"));
        var events = when(state, new RequestReview("abc123", "MANUAL", true));
        assertEquals(List.of(new ReviewRequested("abc123", "MANUAL")), events);
    }

    @Test
    void staleFailureFromSupersededRunIsNoOp() {
        // THE spec bug the round-3 review caught: commit A superseded by B,
        // A's late terminal failure must NOT fail B's run.
        var state = given(new ReviewRequested("abc123", "OPENED"),
                new ReviewSuperseded("abc123"),
                new ReviewRequested("def456", "UPDATED"));
        assertTrue(when(state, new RecordFailure("abc123", "generate", false)).isEmpty());
        assertEquals(Status.REVIEWING, state.status());
        assertEquals("def456", state.currentCommit());
    }

    @Test
    void currentRunTerminalFailureFails() {
        var state = given(new ReviewRequested("abc123", "OPENED"));
        var events = when(state, new RecordFailure("abc123", "generate", false));
        assertEquals(1, events.size());
        assertInstanceOf(ReviewFailedTerminally.class, events.getFirst());
    }

    @Test
    void retryableFailureDoesNotTerminate() {
        var state = given(new ReviewRequested("abc123", "OPENED"));
        assertTrue(when(state, new RecordFailure("abc123", "generate", true)).isEmpty());
    }

    @Test
    void staleCommentsPostedIsIgnored() {
        var state = given(new ReviewRequested("abc123", "OPENED"),
                new ReviewSuperseded("abc123"),
                new ReviewRequested("def456", "UPDATED"));
        assertTrue(when(state, new RecordCommentsPosted("abc123", "c-9", 3)).isEmpty());
    }

    @Test
    void completionRecordsReviewedCommitAndSummary() {
        var state = given(new ReviewRequested("abc123", "OPENED"),
                new ReviewCompleted("abc123", "c-1"));
        assertEquals(Status.COMPLETED, state.status());
        assertTrue(state.reviewedCommits().contains("abc123"));
        assertEquals("c-1", state.summaryCommentId());
    }

    @Test
    void cancelDuringReviewCancels() {
        var state = given(new ReviewRequested("abc123", "OPENED"));
        var events = when(state, new CancelReview("MERGED"));
        assertEquals(List.of(new ReviewCancelled("MERGED")), events);
    }

    @Test
    void cancelWhenNothingInFlightIsNoOp() {
        assertTrue(when(given(), new CancelReview("MERGED")).isEmpty());
        var completed = given(new ReviewRequested("abc123", "OPENED"),
                new ReviewCompleted("abc123", "c-1"));
        assertTrue(when(completed, new CancelReview("DECLINED")).isEmpty());
    }

    @Test
    void reopenedPrStartsFreshAfterCancellation() {
        var state = given(new ReviewRequested("abc123", "OPENED"),
                new ReviewCancelled("DECLINED"));
        assertEquals(Status.CANCELLED, state.status());
        var events = when(state, new RequestReview("def456", "UPDATED", false));
        assertEquals(List.of(new ReviewRequested("def456", "UPDATED")), events);
    }
}
