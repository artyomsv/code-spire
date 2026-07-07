package dev.codespire.orchestrator.eventstore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.EventEnvelope;
import io.quarkus.arc.Arc;
import org.apache.kafka.common.serialization.Deserializer;

import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;

/**
 * cs.events wire format. The envelope's payload is typed by the envelope's own
 * {@code eventType} field (the EventTypes registry), not a JSON discriminator —
 * mirroring how the event store itself resolves payloads (DATA-MODEL §3).
 * NEVER throws (review finding M1): malformed envelopes AND additive future
 * event types a newer producer emits (CONTRACT §11) are logged and skipped —
 * an older projector must not crash on them.
 */
public class EnvelopeDeserializer implements Deserializer<EventEnvelope> {

    private static final Logger LOG = Logger.getLogger(EnvelopeDeserializer.class);

    private final ObjectMapper mapper = resolveMapper();

    private static ObjectMapper resolveMapper() {
        var container = Arc.container();
        if (container != null && container.isRunning()) {
            var instance = container.instance(ObjectMapper.class);
            if (instance.isAvailable()) {
                return instance.get();
            }
        }
        return new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public EventEnvelope deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(data);
            String eventType = root.path("eventType").asText();
            Object payload = mapper.convertValue(root.path("payload"), EventTypes.domainType(eventType));
            return new EventEnvelope(
                    UUID.fromString(root.path("eventId").asText()),
                    eventType,
                    root.path("eventVersion").asInt(1),
                    root.path("streamId").asText(),
                    root.path("sequence").asLong(),
                    Instant.parse(root.path("occurredAt").asText()),
                    root.path("correlationId").isNull() ? null : root.path("correlationId").asText(),
                    root.path("causationId").isNull() || root.path("causationId").isMissingNode()
                            ? null : UUID.fromString(root.path("causationId").asText()),
                    root.path("actor").asText("system"),
                    payload);
        } catch (Exception e) {
            // ERROR (observability rule): the envelope is dropped for good — the
            // read model may miss a terminal status flip, so surface loudly.
            LOG.errorf(e, "Skipping undeserializable envelope on %s", topic);
            return null;
        }
    }
}
