package dev.codespire.contract.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The wire/storage envelope every event travels in (CONTRACT §3).
 * {@code sequence} is per-stream monotonic (domain events only; -1 for
 * integration events). {@code globalPosition} is assigned by the event store
 * on append — store metadata, not part of the published payload.
 */
public record EventEnvelope(UUID eventId,
                            String eventType,
                            int eventVersion,
                            String streamId,
                            long sequence,
                            Instant occurredAt,
                            String correlationId,
                            UUID causationId,
                            String actor,
                            Object payload) {

    public static EventEnvelope domain(String streamId, long sequence, String correlationId,
                                       UUID causationId, Object payload) {
        return new EventEnvelope(UUID.randomUUID(), payload.getClass().getSimpleName(), 1,
                streamId, sequence, Instant.now(), correlationId, causationId, "system", payload);
    }

    public static EventEnvelope integration(String streamId, String correlationId, String actor, Object payload) {
        return new EventEnvelope(UUID.randomUUID(), payload.getClass().getSimpleName(), 1,
                streamId, -1, Instant.now(), correlationId, null, actor, payload);
    }
}
