package dev.codespire.orchestrator.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.codespire.contract.command.ActionCommand;
import io.quarkus.arc.Arc;
import org.apache.kafka.common.serialization.Serializer;

import java.io.UncheckedIOException;

/**
 * cs.commands wire format. Root-level Jackson serialization ignores the
 * interface's @JsonTypeInfo unless written via writerFor(ActionCommand) —
 * this serializer guarantees every record carries the type discriminator.
 */
public class ActionCommandSerializer implements Serializer<ActionCommand> {

    private final ObjectWriter writer = resolveMapper().writerFor(ActionCommand.class);

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
    public byte[] serialize(String topic, ActionCommand command) {
        if (command == null) {
            return null;
        }
        try {
            return writer.writeValueAsBytes(command);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize " + command.getClass().getSimpleName(), e);
        }
    }
}
