package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.codespire.contract.command.ActionCommand;
import io.quarkus.arc.Arc;
import org.apache.kafka.common.serialization.Serializer;

import java.io.UncheckedIOException;

/**
 * ActionCommand JSON with the type discriminator (root-level writerFor).
 * Required here even though the worker only CONSUMES commands: SmallRye's
 * dead-letter queue infers the DLQ serializer by renaming the incoming
 * deserializer class (…Deserializer -> …Serializer), so cs.dlq republishing
 * of a poison command resolves to this class.
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
