package dev.codespire.runworker;

import dev.codespire.contract.event.HarnessImageResult;
import io.quarkus.kafka.client.serialization.ObjectMapperSerializer;

/** Polymorphic JSON on {@code cs.harness-image-results}, keyed by requestId. */
public class HarnessImageResultSerializer extends ObjectMapperSerializer<HarnessImageResult> {
}
