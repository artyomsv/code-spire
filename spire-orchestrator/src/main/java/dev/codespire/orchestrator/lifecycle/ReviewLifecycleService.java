package dev.codespire.orchestrator.lifecycle;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.lifecycle.ReviewLifecycle;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.port.EventStore;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Command side of the ReviewLifecycle aggregate: load -> fold -> decide ->
 * append (optimistic, one retry) -> publish domain events. The single writer
 * of aggregate streams (ADR-010).
 */
@ApplicationScoped
public class ReviewLifecycleService {

    private static final Logger LOG = Logger.getLogger(ReviewLifecycleService.class);

    private final ReviewLifecycle decider = new ReviewLifecycle();

    @Inject
    EventStore eventStore;

    @Inject
    @Channel("events-out")
    Emitter<EventEnvelope> events;

    /** Handles a record command; returns the domain events that were appended (empty = no-op). */
    public List<DomainEvent> handle(String reviewId, RecordCommand command) {
        try {
            return handleOnce(reviewId, command);
        } catch (EventStore.ConcurrencyException conflict) {
            LOG.debugf("Concurrency conflict on %s, retrying once", reviewId);
            return handleOnce(reviewId, command);
        }
    }

    public ReviewState currentState(String reviewId) {
        return fold(eventStore.load(reviewId));
    }

    private List<DomainEvent> handleOnce(String reviewId, RecordCommand command) {
        List<EventEnvelope> history = eventStore.load(reviewId);
        ReviewState state = fold(history);

        List<DomainEvent> newEvents = decider.decide(command, state);
        if (newEvents.isEmpty()) {
            LOG.debugf("%s on %s: no-op", command.getClass().getSimpleName(), reviewId);
            return newEvents;
        }

        long nextSequence = history.size();
        List<EventEnvelope> envelopes = newEvents.stream()
                .map(e -> EventEnvelope.domain(reviewId, -1, reviewId, null, e))
                .toList();
        eventStore.append(reviewId, nextSequence, envelopes);

        for (EventEnvelope envelope : envelopes) {
            // keyed by streamId (= reviewId) for per-PR ordering on cs.events
            events.send(Message.of(envelope,
                    Metadata.of(OutgoingKafkaRecordMetadata.<String>builder()
                            .withKey(envelope.streamId()).build()),
                    () -> CompletableFuture.<Void>completedFuture(null),
                    failure -> {
                        LOG.warnf(failure, "Failed to publish %s", envelope.eventType());
                        return CompletableFuture.<Void>completedFuture(null);
                    }));
        }
        return newEvents;
    }

    private ReviewState fold(List<EventEnvelope> history) {
        ReviewState state = decider.initialState();
        for (EventEnvelope envelope : history) {
            state = decider.evolve(state, (DomainEvent) envelope.payload());
        }
        return state;
    }
}
