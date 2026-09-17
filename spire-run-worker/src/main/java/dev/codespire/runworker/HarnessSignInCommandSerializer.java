package dev.codespire.runworker;

import dev.codespire.contract.command.HarnessSignInCommand;
import io.quarkus.kafka.client.serialization.ObjectMapperSerializer;

/**
 * Exists for the dead-letter queue, not for producing.
 *
 * <p>This worker never sends a {@link HarnessSignInCommand} — the orchestrator does. But a channel
 * with a dead-letter queue must be able to WRITE the record it could not process, and the Kafka
 * extension infers the serializer's name from the deserializer's. Without this class the whole
 * messaging layer fails to start, and every test in the module fails for a reason unrelated to any
 * of them — exactly as {@code RunCommandSerializer} already records.
 */
public class HarnessSignInCommandSerializer extends ObjectMapperSerializer<HarnessSignInCommand> {
}
