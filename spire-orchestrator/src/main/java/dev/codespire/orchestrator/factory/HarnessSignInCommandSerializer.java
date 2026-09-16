package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.HarnessSignInCommand;
import io.quarkus.kafka.client.serialization.ObjectMapperSerializer;

/** Polymorphic JSON on {@code cs.harness-sign-in-commands}, keyed by signInId. */
public class HarnessSignInCommandSerializer extends ObjectMapperSerializer<HarnessSignInCommand> {
}
