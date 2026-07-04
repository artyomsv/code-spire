package dev.codespire.orchestrator.eventstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.EventEnvelope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QA gap #4: the cs.events deserializer must never kill the projector —
 * malformed envelopes and additive future event types are skipped (null),
 * and DomainEvent's EventTypes registry must stay complete (finding M2).
 */
class EnvelopeDeserializerTest {

    private final EnvelopeDeserializer deserializer = new EnvelopeDeserializer();
    // match the production Quarkus mapper: ISO-8601 dates, not numeric timestamps
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void roundTripsAValidEnvelope() throws Exception {
        EventEnvelope original = EventEnvelope.domain("review::sandbox/demo-repo#1", 0,
                "review::sandbox/demo-repo#1", null,
                new DomainEvent.ReviewRequested("abc123", "OPENED"));

        EventEnvelope back = deserializer.deserialize("cs.events", mapper.writeValueAsBytes(original));
        assertEquals(original.eventId(), back.eventId());
        assertEquals(original.payload(), back.payload());
    }

    @Test
    void garbageBytesAreSkippedNotFatal() {
        assertNull(deserializer.deserialize("cs.events", "not json at all".getBytes(StandardCharsets.UTF_8)));
        assertNull(deserializer.deserialize("cs.events", new byte[] {(byte) 0x92, 3, 7}));
    }

    @Test
    void unknownFutureEventTypeIsSkippedNotFatal() {
        // an ADDITIVE new domain event from a newer producer (CONTRACT §11)
        String json = """
                { "eventId": "6f9619ff-8b86-d011-b42d-00cf4fc964ff", "eventType": "SomeFutureEvent",
                  "eventVersion": 1, "streamId": "s", "sequence": 0,
                  "occurredAt": "2026-01-01T00:00:00Z", "actor": "system", "payload": {} }
                """;
        assertNull(deserializer.deserialize("cs.events", json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void nullDataStaysNull() {
        assertNull(deserializer.deserialize("cs.events", null));
    }

    @Test
    void everyDomainEventSubtypeIsRegisteredInEventTypes() {
        // finding M2: adding a DomainEvent subtype without registering it in
        // EventTypes compiles and serializes but fails on load/deserialize.
        for (Class<?> subtype : DomainEvent.class.getPermittedSubclasses()) {
            assertEquals(subtype, EventTypes.domainType(subtype.getSimpleName()),
                    subtype.getSimpleName() + " is not registered in EventTypes.DOMAIN");
        }
        assertTrue(DomainEvent.class.getPermittedSubclasses().length > 0);
    }
}
