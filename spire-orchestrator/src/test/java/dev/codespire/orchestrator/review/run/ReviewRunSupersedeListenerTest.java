package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.orchestrator.llm.ReviewRuns;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@code ReviewSuperseded} ends the review's current round (review of PR #367): the listener cancels that round, read
 * from the supersede's own position in the stream, never the commit, which a forced re-run keeps.
 */
class ReviewRunSupersedeListenerTest {

    private static final String REVIEW = "review::TEST-WS/TEST-REPO#7";

    private final List<String> superseded = new ArrayList<>();

    private int round = 1;

    private ReviewRunSupersedeListener listener() {
        ReviewRunSupersedeListener listener = new ReviewRunSupersedeListener();
        listener.cancellation = new ReviewRunCancellation() {
            @Override public void supersede(String reviewId, int supersededRound) { superseded.add(reviewId + "@" + supersededRound); }
            @Override public void close(String reviewId) { throw new AssertionError("a supersede is not a close"); }
        };
        // The real one reads event_log; this answers the round the supersede at a position ended.
        listener.rounds = new ReviewRuns() {
            @Override public int roundSupersededAt(String reviewId, long sequence) { return sequence == 4 ? round : -1; }
            @Override public int currentRun(String reviewId) { throw new AssertionError("the round is read by position"); }
        };
        return listener;
    }

    @Test
    void aSupersededReviewCancelsTheRoundItEnded() {
        listener().on(EventEnvelope.domain(REVIEW, 4, "TEST-correlation", UUID.randomUUID(),
                new DomainEvent.ReviewSuperseded("a".repeat(40))));

        assertEquals(List.of(REVIEW + "@1"), superseded);
    }

    /** A round that could not be read cancels nothing rather than guessing one: a guess could stop the current round. */
    @Test
    void anUnreadableRoundCancelsNothing() {
        round = ReviewRuns.UNKNOWN_RUN;

        listener().on(EventEnvelope.domain(REVIEW, 4, "TEST-correlation", UUID.randomUUID(),
                new DomainEvent.ReviewSuperseded("a".repeat(40))));

        assertEquals(List.of(), superseded);
    }

    @Test
    void anotherEventOrAPoisonRecordCancelsNothing() {
        listener().on(EventEnvelope.domain(REVIEW, 5, "TEST-correlation", UUID.randomUUID(),
                new DomainEvent.ReviewCompleted("a".repeat(40), "TEST-summary")));
        listener().on(null);

        assertEquals(List.of(), superseded);
    }
}
