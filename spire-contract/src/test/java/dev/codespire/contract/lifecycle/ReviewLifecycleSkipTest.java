package dev.codespire.contract.lifecycle;

import dev.codespire.contract.command.RecordCommand.RequestReview;
import dev.codespire.contract.command.RecordCommand.SkipReview;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.DomainEvent.ReviewCancelled;
import dev.codespire.contract.event.DomainEvent.ReviewRequested;
import dev.codespire.contract.event.DomainEvent.ReviewSuperseded;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review waits for CI: a skip for failed checks ends only the current run, and a new commit starts afresh. */
class ReviewLifecycleSkipTest {

    private static final String FAILED = "CHECKS_FAILED";
    private final ReviewLifecycle decider = new ReviewLifecycle();

    @Test
    void eachRequestedRunIsANewRound() {
        ReviewState first = reviewing("c1");
        assertEquals(1, first.round());
        ReviewState rerun = fold(first, decider.decide(new RequestReview("c1", "rerun", true), first));
        assertEquals(2, rerun.round(), "a forced re-run on the same head is another round");
        assertEquals(2, decider.evolve(rerun, new ReviewCancelled(FAILED)).round(), "only a request counts");
    }

    @Test
    void aSkipEndsTheCurrentRun() {
        ReviewState state = reviewing("c1");
        List<DomainEvent> events = decider.decide(new SkipReview("c1", 1, FAILED), state);
        assertEquals(List.of(new ReviewCancelled(FAILED)), events);
        assertEquals(ReviewState.Status.CANCELLED, decider.evolve(state, events.getFirst()).status());
    }

    @Test
    void aSkipForAReplacedHeadIsANoOp() {
        assertTrue(decider.decide(new SkipReview("c1", 1, FAILED), reviewing("c2")).isEmpty());
    }

    /** The same head re-run: the old round's skip never ends the new round's run. */
    @Test
    void aSkipForAnEarlierRoundOfTheSameHeadIsANoOp() {
        ReviewState first = reviewing("c1");
        ReviewState rerun = fold(first, List.of(new ReviewSuperseded("c1"), new ReviewRequested("c1", "rerun")));
        assertTrue(decider.decide(new SkipReview("c1", 1, FAILED), rerun).isEmpty());
        assertEquals(List.of(new ReviewCancelled(FAILED)), decider.decide(new SkipReview("c1", 2, FAILED), rerun));
    }

    @Test
    void aSecondSkipIsANoOp() {
        ReviewState skipped = decider.evolve(reviewing("c1"), new ReviewCancelled(FAILED));
        assertTrue(decider.decide(new SkipReview("c1", 1, FAILED), skipped).isEmpty());
    }

    /** A skipped head is not reviewed again on a redelivery; a new commit is. */
    @Test
    void aSkippedHeadStaysSkippedUntilANewCommit() {
        ReviewState skipped = decider.evolve(reviewing("c1"), new ReviewCancelled(FAILED));
        assertTrue(decider.decide(new RequestReview("c1", "UPDATED", false), skipped).isEmpty());
        assertEquals(List.of(new ReviewRequested("c2", "UPDATED")),
                decider.decide(new RequestReview("c2", "UPDATED", false), skipped));
    }

    private ReviewState reviewing(String commit) {
        return decider.evolve(decider.initialState(), new ReviewRequested(commit, "OPENED"));
    }

    private ReviewState fold(ReviewState state, List<DomainEvent> events) {
        ReviewState folded = state;
        for (DomainEvent event : events) folded = decider.evolve(folded, event);
        return folded;
    }
}
