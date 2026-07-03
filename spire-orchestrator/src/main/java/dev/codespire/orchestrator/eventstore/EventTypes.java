package dev.codespire.orchestrator.eventstore;

import dev.codespire.contract.event.DomainEvent;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * event_type string <-> payload class registry for (de)serialization.
 * Breaking payload changes bump eventVersion + ship an upcaster (CONTRACT §11);
 * additive fields need nothing.
 */
public final class EventTypes {

    private static final Map<String, Class<? extends DomainEvent>> DOMAIN = Stream.of(
            DomainEvent.ReviewRequested.class,
            DomainEvent.ReviewSuperseded.class,
            DomainEvent.ReviewOutcomeRecorded.class,
            DomainEvent.ReviewCompleted.class,
            DomainEvent.ReviewFailedTerminally.class,
            DomainEvent.ReviewCancelled.class,
            DomainEvent.ThreadOpened.class,
            DomainEvent.FollowUpRecorded.class
    ).collect(Collectors.toUnmodifiableMap(Class::getSimpleName, Function.identity()));

    private EventTypes() {
    }

    public static Class<? extends DomainEvent> domainType(String eventType) {
        Class<? extends DomainEvent> type = DOMAIN.get(eventType);
        if (type == null) {
            throw new IllegalArgumentException("Unknown domain event type: " + eventType);
        }
        return type;
    }
}
