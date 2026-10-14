package dev.codespire.runworker;

import dev.codespire.contract.event.RunVerification;
import io.quarkus.kafka.client.serialization.ObjectMapperSerializer;

/** Polymorphic JSON on {@code cs.run-verifications}, keyed by runId. */
public class RunVerificationSerializer extends ObjectMapperSerializer<RunVerification> {
}
