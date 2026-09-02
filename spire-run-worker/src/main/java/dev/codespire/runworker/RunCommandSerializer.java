package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import io.quarkus.kafka.client.serialization.ObjectMapperSerializer;

/**
 * Exists for the dead-letter queue, not for producing.
 *
 * <p>This worker never sends a RunCommand. But a channel with a dead-letter-queue must be able to
 * WRITE the record it could not process, and the Kafka extension infers the serializer's name from
 * the deserializer's — so without this class the whole messaging layer fails to start with a
 * ClassNotFoundException, and every test in the module fails for a reason unrelated to any of them.
 */
public class RunCommandSerializer extends ObjectMapperSerializer<RunCommand> {
}
