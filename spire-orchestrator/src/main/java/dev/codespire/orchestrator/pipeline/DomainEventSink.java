package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Projector consuming cs.events: folds published domain events into the
 * timeline read model. The dedicated read-model service (spire-ui) takes this
 * seam over in P2.
 */
@ApplicationScoped
public class DomainEventSink {

    @Inject
    TimelineBroadcaster timeline;

    @Incoming("events-in")
    @Blocking
    public void on(EventEnvelope envelope) {
        if (envelope == null) {
            return; // undeserializable envelope already logged by the deserializer
        }
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
