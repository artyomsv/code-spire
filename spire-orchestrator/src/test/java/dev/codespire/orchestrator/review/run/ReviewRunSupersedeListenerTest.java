package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.EventEnvelope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code ReviewSuperseded} names the OLD commit, so the listener cancels the run that reads it, and nothing else. */
class ReviewRunSupersedeListenerTest {

    private static final String REVIEW = "review::TEST-WS/TEST-REPO#7";

    private final List<String> superseded = new ArrayList<>();

    private ReviewRunSupersedeListener listener() {
        ReviewRunSupersedeListener listener = new ReviewRunSupersedeListener();
        listener.cancellation = new ReviewRunCancellation() {
            @Override public void supersede(String reviewId, String supersededCommit) { superseded.add(reviewId + "@" + supersededCommit); }
            @Override public void close(String reviewId) { throw new AssertionError("a supersede is not a close"); }
        };
        return listener;
    }

    @Test
    void aSupersededReviewCancelsTheRunOfTheReplacedHead() {
        listener().on(EventEnvelope.domain(REVIEW, 4, "TEST-correlation", UUID.randomUUID(),
                new DomainEvent.ReviewSuperseded("a".repeat(40))));

        assertEquals(List.of(REVIEW + "@" + "a".repeat(40)), superseded);
    }

    @Test
    void anotherEventOrAPoisonRecordCancelsNothing() {
        listener().on(EventEnvelope.domain(REVIEW, 5, "TEST-correlation", UUID.randomUUID(),
                new DomainEvent.ReviewCompleted("a".repeat(40), "TEST-summary")));
        listener().on(null);

        assertEquals(List.of(), superseded);
    }
}
