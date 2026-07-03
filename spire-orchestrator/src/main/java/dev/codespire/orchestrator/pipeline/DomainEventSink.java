package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consumer of the "events" channel (= cs.events topic at P1). In Phase 0 its
 * job is projection: fold published domain events into the timeline read model.
 * At P1 this seam becomes the Kafka publisher / projector split.
 */
@ApplicationScoped
public class DomainEventSink {

    @Inject
    TimelineBroadcaster timeline;

    @Incoming("events")
    @Blocking
    public void on(EventEnvelope envelope) {
        timeline.record("domain", envelope.eventType(), envelope.streamId(), describe(envelope.payload()));
    }

    private String describe(Object payload) {
        return switch (payload) {
            case DomainEvent.ReviewRequested e -> "commit " + e.commit() + " (" + e.trigger() + ")";
            case DomainEvent.ReviewSuperseded e -> "superseded " + e.commit();
            case DomainEvent.ReviewCompleted e -> "commit " + e.commit();
            case DomainEvent.ReviewCancelled e -> e.reason();
            default -> "";
        };
    }
}
