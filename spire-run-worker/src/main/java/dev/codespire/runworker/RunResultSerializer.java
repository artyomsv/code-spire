package dev.codespire.runworker;

import dev.codespire.contract.event.RunResult;
import io.quarkus.kafka.client.serialization.ObjectMapperSerializer;

/** Polymorphic JSON on {@code cs.run-results}, keyed by runId. */
public class RunResultSerializer extends ObjectMapperSerializer<RunResult> {
}
