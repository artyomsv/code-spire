package dev.codespire.orchestrator.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.codespire.contract.event.IntegrationEvent;
import io.quarkus.arc.Arc;
import org.apache.kafka.common.serialization.Serializer;

import java.io.UncheckedIOException;

/**
 * cs.integration wire format. Root-level Jackson serialization ignores the
 * interface's @JsonTypeInfo unless written via writerFor(IntegrationEvent) —
 * this serializer guarantees every record carries the type discriminator.
 */
public class IntegrationEventSerializer implements Serializer<IntegrationEvent> {

    private final ObjectWriter writer = resolveMapper().writerFor(IntegrationEvent.class);

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
    public byte[] serialize(String topic, IntegrationEvent event) {
        if (event == null) {
            return null;
        }
        try {
            return writer.writeValueAsBytes(event);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize " + event.getClass().getSimpleName(), e);
        }
    }
}
