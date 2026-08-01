package dev.codespire.orchestrator.eventstore;

import dev.codespire.contract.event.DomainEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-013 gate's event-store half: {@link EventTypes} must name every {@link DomainEvent}.
 *
 * <p>Unlike the Kafka hierarchies, domain events carry no {@code @JsonSubTypes} — the event store
 * keys them by simple name through a list maintained BY HAND, in a different module from the sealed
 * interface it mirrors. Adding a domain event compiles, passes the decider tests, and appends
 * happily; the omission only surfaces when something replays the stream and
 * {@code domainType(...)} throws "Unknown domain event type" on data already written and no longer
 * readable.
 *
 * <p>The counterpart to {@code ContractSchemaSnapshotTest}, which cannot see this from
 * {@code spire-contract} because the registry lives here.
 */
class EventTypesCoverageTest {

    @Test
    void everyDomainEventIsRegistered() {
        Class<?>[] permitted = DomainEvent.class.getPermittedSubclasses();
        assertNotNull(permitted, "DomainEvent is not sealed — this test can no longer enumerate it");
        assertTrue(permitted.length > 0, "DomainEvent permits no subtypes");

        for (Class<?> subtype : permitted) {
            String eventType = subtype.getSimpleName();
            assertDoesNotThrow(() -> EventTypes.domainType(eventType),
                    subtype.getSimpleName() + " is a DomainEvent but is missing from EventTypes — the "
                            + "event store cannot read it back once it has been appended");
            assertEquals(subtype, EventTypes.domainType(eventType),
                    eventType + " resolves to a different class than the one it names");
        }
    }

    /**
     * An unknown type must fail loudly rather than resolve to null, which would surface as a
     * NullPointerException somewhere further along the replay instead of naming the missing type.
     */
    @Test
    void anUnregisteredTypeIsRejectedByName() {
        IllegalArgumentException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> EventTypes.domainType("NoSuchEventWasEverAppended"));
        assertTrue(thrown.getMessage().contains("NoSuchEventWasEverAppended"),
                "the message must name the type so a failed replay is diagnosable");
    }
}
