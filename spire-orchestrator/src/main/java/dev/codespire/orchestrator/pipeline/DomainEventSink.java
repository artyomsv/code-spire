package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
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

    @Inject
    ReviewProjection projection;

    @Incoming("events-in")
    @Blocking
    public void on(EventEnvelope envelope) {
        if (envelope == null) {
            return; // undeserializable envelope already logged by the deserializer
        }
        String reviewId = envelope.streamId();
        String detail = describe(envelope.payload());
        timeline.record("domain", envelope.eventType(), reviewId, detail);
        projection.appendEvent(reviewId, "domain", envelope.eventType(), detail);
        // Terminal transitions own the read-model status; ReviewRequested's
        // initial "reviewing" is set by IntegrationSaga, so it's not re-applied here.
        switch (envelope.payload()) {
            case DomainEvent.ReviewCompleted ignored ->
                    projection.updateStatus(reviewId, "completed", ReviewProjection.STAGE_DONE);
            case DomainEvent.ReviewFailedTerminally ignored -> projection.updateStatus(reviewId, "failed");
            case DomainEvent.ReviewCancelled ignored -> projection.updateStatus(reviewId, "cancelled");
            case DomainEvent.ReviewSuperseded ignored -> projection.updateStatus(reviewId, "superseded");
            default -> { /* non-terminal: event already appended above */ }
        }
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
