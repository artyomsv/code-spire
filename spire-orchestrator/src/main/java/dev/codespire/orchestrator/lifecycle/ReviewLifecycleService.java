package dev.codespire.orchestrator.lifecycle;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.lifecycle.ReviewLifecycle;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.port.EventStore;
import dev.codespire.orchestrator.pipeline.KafkaSends;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.util.List;

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

    /**
     * A run requested, and the state the request left: folded from the very history the append was made on, so its
     * round is the accepted run's identity (review waits for CI), never a later read's.
     */
    public record Requested(List<DomainEvent> events, ReviewState state) {
        public boolean accepted() {
            return events.stream().anyMatch(DomainEvent.ReviewRequested.class::isInstance);
        }
    }

    /** Handles a run request like {@link #handle}, answering the state it left as well. */
    public Requested request(String reviewId, RecordCommand.RequestReview command) {
        try {
            return requestOnce(reviewId, command);
        } catch (EventStore.ConcurrencyException conflict) {
            LOG.debugf("Concurrency conflict on %s, retrying once", reviewId);
            return requestOnce(reviewId, command);
        }
    }

    private Requested requestOnce(String reviewId, RecordCommand command) {
        List<EventEnvelope> history = eventStore.load(reviewId);
        ReviewState state = fold(history);
        List<DomainEvent> appended = append(reviewId, history, decider.decide(command, state));
        for (DomainEvent event : appended) state = decider.evolve(state, event);
        return new Requested(appended, state);
    }

    private List<DomainEvent> handleOnce(String reviewId, RecordCommand command) {
        List<EventEnvelope> history = eventStore.load(reviewId);
        return append(reviewId, history, decider.decide(command, fold(history)));
    }

    /** Appends {@code newEvents} after {@code history} (optimistic) and publishes them; empty is a no-op. */
    private List<DomainEvent> append(String reviewId, List<EventEnvelope> history, List<DomainEvent> newEvents) {
        if (newEvents.isEmpty()) {
            LOG.debugf("%s: no-op", reviewId);
            return newEvents;
        }

        long nextSequence = history.size();
        List<EventEnvelope> envelopes = newEvents.stream()
                .map(e -> EventEnvelope.domain(reviewId, -1, reviewId, null, e))
                .toList();
        eventStore.append(reviewId, nextSequence, envelopes);

        for (EventEnvelope envelope : envelopes) {
            // keyed by streamId (= reviewId) for per-PR ordering on cs.events.
            // Awaits the broker ack: a failed publish throws so the caller sees it
            // (saga -> cs.dlq, REST -> 5xx) instead of the read model silently
            // diverging from the appended event log.
            KafkaSends.sendAndAwait(events, envelope.streamId(), envelope,
                    envelope.eventType() + " for " + envelope.streamId());
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
