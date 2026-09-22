package dev.codespire.runworker;

import dev.codespire.contract.command.HarnessImageCommand;
import io.quarkus.kafka.client.serialization.ObjectMapperSerializer;

/**
 * Exists for the dead-letter queue, not for producing. The Kafka extension derives the serializer's name
 * from the deserializer's, and without it the whole messaging layer refuses to start.
 */
public class HarnessImageCommandSerializer extends ObjectMapperSerializer<HarnessImageCommand> {
}
