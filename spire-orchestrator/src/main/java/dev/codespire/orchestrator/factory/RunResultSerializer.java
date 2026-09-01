package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.codespire.contract.event.RunResult;
import io.quarkus.arc.Arc;
import org.apache.kafka.common.serialization.Serializer;

import java.io.UncheckedIOException;

/**
 * The orchestrator never publishes a {@link RunResult} — the worker does — but the dead-letter
 * queue on {@code run-results-in} re-serializes a record whose processing failed, and SmallRye
 * resolves that serializer by rewriting the configured deserializer's class name
 * ({@code RunResultDeserializer} → {@code RunResultSerializer}). Without this class the channel
 * refuses to start at all: {@code SRMSG18010: Unable to create an instance of RunResultSerializer}.
 * Written via {@code writerFor(RunResult)} so the type discriminator survives the round trip,
 * exactly as {@code RunCommandSerializer} does.
 */
public class RunResultSerializer implements Serializer<RunResult> {

    private final ObjectWriter writer = resolveMapper().writerFor(RunResult.class);

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
    public byte[] serialize(String topic, RunResult result) {
        if (result == null) {
            return null;
        }
        try {
            return writer.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize " + result.getClass().getSimpleName(), e);
        }
    }
}
