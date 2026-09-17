package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.codespire.contract.event.HarnessSignInResult;
import io.quarkus.arc.Arc;
import org.apache.kafka.common.serialization.Serializer;

import java.io.UncheckedIOException;

/**
 * The orchestrator never publishes a {@link HarnessSignInResult} — the worker does — but the
 * dead-letter queue on {@code harness-sign-in-results-in} re-serializes a record whose processing
 * failed, and SmallRye resolves that serializer by rewriting the configured deserializer's class name
 * ({@code HarnessSignInResultDeserializer} → {@code HarnessSignInResultSerializer}). Without this class
 * the channel refuses to start at all, and the whole service with it.
 *
 * <p>The same trap {@code RunResultSerializer} documents, met again here for the same reason: the name
 * is derived, so a channel with a dead-letter queue needs both halves in one package even when only
 * one direction is ever used.
 */
public class HarnessSignInResultSerializer implements Serializer<HarnessSignInResult> {

    private final ObjectWriter writer = resolveMapper().writerFor(HarnessSignInResult.class);

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
    public byte[] serialize(String topic, HarnessSignInResult result) {
        if (result == null) {
            return null;
        }
        try {
            // writerFor the interface, so the type discriminator survives a dead-letter round trip.
            return writer.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize " + result.getClass().getSimpleName(), e);
        }
    }
}
