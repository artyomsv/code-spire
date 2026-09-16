package dev.codespire.runworker;

import dev.codespire.contract.event.HarnessSignInResult;
import io.quarkus.kafka.client.serialization.ObjectMapperSerializer;

/** Polymorphic JSON on {@code cs.harness-sign-in-results}, keyed by signInId. */
public class HarnessSignInResultSerializer extends ObjectMapperSerializer<HarnessSignInResult> {
}
