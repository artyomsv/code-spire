package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.HarnessImageCommand;
import io.quarkus.kafka.client.serialization.ObjectMapperSerializer;

/** Polymorphic JSON on {@code cs.harness-image-commands}, keyed by harness. */
public class HarnessImageCommandSerializer extends ObjectMapperSerializer<HarnessImageCommand> {
}
