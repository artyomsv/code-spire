package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.codespire.contract.command.RunCommand;
import io.quarkus.arc.Arc;
import org.apache.kafka.common.serialization.Serializer;

import java.io.UncheckedIOException;

/**
 * cs.run-commands wire format. Root-level Jackson serialization ignores the interface's
 * {@code @JsonTypeInfo} unless written via {@code writerFor(RunCommand)} — this serializer guarantees
 * every record carries the type discriminator, exactly as {@code IntegrationEventSerializer} does.
 */
public class RunCommandSerializer implements Serializer<RunCommand> {

    private final ObjectWriter writer = resolveMapper().writerFor(RunCommand.class);

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
    public byte[] serialize(String topic, RunCommand command) {
        if (command == null) {
            return null;
        }
        try {
            return writer.writeValueAsBytes(command);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize " + command.getClass().getSimpleName(), e);
        }
    }
}
