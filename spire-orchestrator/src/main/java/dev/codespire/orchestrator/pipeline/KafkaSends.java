package dev.codespire.orchestrator.pipeline;

import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Send a keyed record and await the broker ack. A nack or timeout throws — so a
 * failed publish is never silently lost: inside an {@code @Incoming} consumer
 * the exception engages the channel's failure-strategy (cs.dlq, ADR-013), and on
 * the REST path it surfaces as a 5xx instead of a fake success. Mirrors the
 * gateway's WebhookResource ack-before-202.
 */
public final class KafkaSends {

    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);

    private KafkaSends() {
    }

    public static <T> void sendAndAwait(Emitter<T> emitter, String key, T payload, String description) {
        CompletableFuture<Void> ack = new CompletableFuture<>();
        emitter.send(Message.of(payload,
                Metadata.of(OutgoingKafkaRecordMetadata.<String>builder().withKey(key).build()),
                () -> {
                    ack.complete(null);
                    return CompletableFuture.<Void>completedFuture(null);
                },
                failure -> {
                    ack.completeExceptionally(failure);
                    return CompletableFuture.<Void>completedFuture(null);
                }));
        awaitAck(ack, description);
    }

    private static void awaitAck(CompletableFuture<Void> ack, String description) {
        try {
            ack.get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted awaiting broker ack for " + description, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Broker rejected " + description, e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "No broker ack within " + ACK_TIMEOUT.toSeconds() + "s for " + description, e);
        }
    }
}
