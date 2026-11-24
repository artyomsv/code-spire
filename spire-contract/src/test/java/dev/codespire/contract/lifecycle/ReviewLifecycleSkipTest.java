package dev.codespire.contract.lifecycle;

import dev.codespire.contract.command.RecordCommand.RequestReview;
import dev.codespire.contract.command.RecordCommand.SkipReview;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.DomainEvent.ReviewCancelled;
import dev.codespire.contract.event.DomainEvent.ReviewRequested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review waits for CI: a skip for failed checks ends only the current head's run, and a new commit starts afresh. */
class ReviewLifecycleSkipTest {

    private final ReviewLifecycle decider = new ReviewLifecycle();

    @Test
    void aSkipEndsTheCurrentHeadsRun() {
        ReviewState state = reviewing("c1");
        List<DomainEvent> events = decider.decide(new SkipReview("c1", "CHECKS_FAILED"), state);
        assertEquals(List.of(new ReviewCancelled("CHECKS_FAILED")), events);
        assertEquals(ReviewState.Status.CANCELLED, decider.evolve(state, events.getFirst()).status());
    }

    @Test
    void aSkipForAReplacedHeadIsANoOp() {
        assertTrue(decider.decide(new SkipReview("c1", "CHECKS_FAILED"), reviewing("c2")).isEmpty());
    }

    @Test
    void aSecondSkipIsANoOp() {
        ReviewState skipped = decider.evolve(reviewing("c1"), new ReviewCancelled("CHECKS_FAILED"));
        assertTrue(decider.decide(new SkipReview("c1", "CHECKS_FAILED"), skipped).isEmpty());
    }

    /** A skipped head is not reviewed again on a redelivery; a new commit is. */
    @Test
    void aSkippedHeadStaysSkippedUntilANewCommit() {
        ReviewState skipped = decider.evolve(reviewing("c1"), new ReviewCancelled("CHECKS_FAILED"));
        assertTrue(decider.decide(new RequestReview("c1", "UPDATED", false), skipped).isEmpty());
        assertEquals(List.of(new ReviewRequested("c2", "UPDATED")),
                decider.decide(new RequestReview("c2", "UPDATED", false), skipped));
    }

    private ReviewState reviewing(String commit) {
        return decider.evolve(decider.initialState(), new ReviewRequested(commit, "OPENED"));
    }
}
