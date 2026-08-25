package dev.codespire.contract.lifecycle;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.DomainEvent.ConversationFindingRaised;
import dev.codespire.contract.event.DomainEvent.ReviewCancelled;
import dev.codespire.contract.event.DomainEvent.ReviewCompleted;
import dev.codespire.contract.event.DomainEvent.ReviewFailedTerminally;
import dev.codespire.contract.event.DomainEvent.ReviewRequested;
import dev.codespire.contract.event.DomainEvent.ReviewSuperseded;
import dev.codespire.contract.event.DomainEvent.ThreadOpened;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.command.RecordCommand.CancelReview;
import dev.codespire.contract.command.RecordCommand.RaiseConversationFinding;
import dev.codespire.contract.command.RecordCommand.RecordCommentsPosted;
import dev.codespire.contract.command.RecordCommand.RecordFailure;
import dev.codespire.contract.command.RecordCommand.RequestReview;
import dev.codespire.contract.lifecycle.ReviewState.Status;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.ThreadRef;
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

    @Test
    void raisingAConversationFindingAppendsIt() {
        var state = given(new ReviewRequested("c1", "OPENED"));

        var events = when(state, new RaiseConversationFinding(
                new ThreadRef("t-900"), "src/Foo.java", 44, Severity.MINOR,
                "shadows the field", "c-901"));

        assertEquals(List.of(new ConversationFindingRaised(
                new ThreadRef("t-900"), "src/Foo.java", 44, Severity.MINOR, "c-901")), events);
    }

    @Test
    void raisingTheSameCommentTwiceAppendsNothingTheSecondTime() {
        // ManualCommandReceived is at-least-once over Kafka. The worker's claim guards the SCM
        // post; only the aggregate can stop a redelivery appending a second finding.
        var state = given(new ReviewRequested("c1", "OPENED"));
        var cmd = new RaiseConversationFinding(new ThreadRef("t-900"), "src/Foo.java", 44,
                Severity.MINOR, "shadows the field", "c-901");

        var after = decider.evolve(state, when(state, cmd).getFirst());

        assertTrue(when(after, cmd).isEmpty());
    }

    @Test
    void aDifferentCommentOnTheSameThreadStillAppends() {
        // The key is the triggering comment, not the thread: a second /finding in one discussion
        // is a second finding, and keying on the thread would silently drop it.
        var state = given(new ReviewRequested("c1", "OPENED"));
        var first = new RaiseConversationFinding(new ThreadRef("t-900"), "src/Foo.java", 44,
                Severity.MINOR, "shadows the field", "c-901");
        var after = decider.evolve(state, when(state, first).getFirst());

        var second = new RaiseConversationFinding(new ThreadRef("t-900"), "src/Foo.java", 51,
                Severity.MAJOR, "and this leaks", "c-902");

        assertEquals(List.of(new ConversationFindingRaised(
                new ThreadRef("t-900"), "src/Foo.java", 51, Severity.MAJOR, "c-902")),
                when(after, second));
    }

    @Test
    void aConversationFindingLeavesTheRunUntouched() {
        // ReviewOutcomeRecorded answers "how many findings did the review of this commit
        // produce". A conversation finding did not come from that call and must not rewrite it.
        var state = given(new ReviewRequested("c1", "OPENED"));
        var after = decider.evolve(state, new ConversationFindingRaised(
                new ThreadRef("t-900"), "src/Foo.java", 44, Severity.MINOR, "c-901"));

        assertEquals(Status.REVIEWING, after.status());
        assertEquals("c1", after.currentCommit());
    }

    /**
     * {@code raisedFindingComments()} is an immutable {@code Set}, whose {@code contains(null)}
     * throws rather than answering false — and {@code Set.copyOf} throws on a null element too, so
     * both {@code decide} and {@code evolve} need their own guard. Not reachable through the normal
     * ingress today, but a hand-crafted command (or a future caller) must not crash the decider on
     * a null triggering comment id.
     */
    @Test
    void aNullTriggeringCommentIdDoesNotCrashTheDecider() {
        var state = given(new ReviewRequested("c1", "OPENED"));
        var cmd = new RaiseConversationFinding(new ThreadRef("t-900"), "src/Foo.java", 44,
                Severity.MINOR, "shadows the field", null);

        var events = when(state, cmd);

        assertEquals(List.of(new ConversationFindingRaised(
                new ThreadRef("t-900"), "src/Foo.java", 44, Severity.MINOR, null)), events);
        var after = decider.evolve(state, events.getFirst());
        assertTrue(after.raisedFindingComments().isEmpty(),
                "a null comment id cannot serve as an idempotency key -- nothing to add");
    }

    @Test
    void raisingAConversationFindingPreservesTheRestOfState() {
        // withRaisedFinding must copy reviewedCommits, threads, and summaryCommentId through
        // unchanged, not just build a fresh state around the new comment id. A dropped
        // reviewedCommits or summaryCommentId would leave every test above green, since none of
        // them evolve a ConversationFindingRaised from a state that already carries one.
        var state = given(new ReviewRequested("c1", "OPENED"),
                new ThreadOpened(new ThreadRef("t-1"), "c-1"),
                new ReviewCompleted("c1", "s-1"));

        var after = decider.evolve(state, new ConversationFindingRaised(
                new ThreadRef("t-900"), "src/Foo.java", 44, Severity.MINOR, "c-901"));

        assertTrue(after.reviewedCommits().contains("c1"));
        assertEquals("s-1", after.summaryCommentId());
        assertTrue(after.threads().containsKey("t-1"));
    }
}
