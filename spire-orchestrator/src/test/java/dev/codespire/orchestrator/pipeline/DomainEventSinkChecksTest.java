package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.review.checks.ChecksWaitStatus;
import dev.codespire.orchestrator.review.checks.ChecksWaitStore;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The read-model statuses review waits for CI adds (V121): a skip for failed checks projects {@code skipped}, a close
 * still projects {@code cancelled}, and a superseded head whose successor waits for its checks keeps that status.
 */
class DomainEventSinkChecksTest {

    private static final String REVIEW_ID = "github:TEST-owner/TEST-repo#7";

    private final List<String> statuses = new ArrayList<>();
    private boolean waiting;

    @Test
    void aSkipForFailedChecksIsProjectedSkipped() {
        sink().on(event(new DomainEvent.ReviewCancelled(ChecksWaitStatus.CHECKS_FAILED)));
        assertEquals(List.of(ChecksWaitStatus.SKIPPED), statuses);
    }

    @Test
    void aCloseIsStillProjectedCancelled() {
        sink().on(event(new DomainEvent.ReviewCancelled("TEST-closed")));
        assertEquals(List.of("cancelled"), statuses);
    }

    @Test
    void aSupersededHeadWhoseSuccessorWaitsKeepsTheWaitingStatus() {
        waiting = true;
        sink().on(event(new DomainEvent.ReviewSuperseded("TEST-old-head")));
        assertEquals(List.of(), statuses);
    }

    @Test
    void aSupersededHeadWithNoWaitIsProjectedSuperseded() {
        sink().on(event(new DomainEvent.ReviewSuperseded("TEST-old-head")));
        assertEquals(List.of("superseded"), statuses);
    }

    private static EventEnvelope event(Object payload) {
        return EventEnvelope.domain(REVIEW_ID, 2, "TEST-correlation", null, payload);
    }

    /** Every method the projection path reaches is overridden: an un-overridden one would open a real database. */
    private DomainEventSink sink() {
        DomainEventSink sink = new DomainEventSink();
        sink.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
        sink.projection = new ReviewProjection() {
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
            }

            @Override
            public void updateStatus(String reviewId, String status) {
                statuses.add(status);
            }
        };
        sink.checksWaits = new ChecksWaitStore() {
            @Override
            public boolean isWaiting(String reviewId) {
                return waiting;
            }
        };
        return sink;
    }
}
