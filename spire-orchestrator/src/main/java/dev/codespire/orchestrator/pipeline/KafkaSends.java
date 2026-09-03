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

    /**
     * Every path throws {@link BrokerAckFailure}, which is an {@link IllegalStateException} — so
     * existing callers are unaffected — and carries whether the record might have landed anyway.
     *
     * <p>Two of these three outcomes say nothing at all about the record: a wait that elapsed and a
     * thread that was interrupted are facts about us, not about the partition. Only the third can
     * ever prove the record never left, and only for the few causes {@link BrokerAckFailure} lists as
     * impossible after a send. Reporting all three as one failure is what let the factory's dispatch
     * record every unacknowledged send as a definite miss, which is the optimistic reading and the
     * expensive one to be wrong about.
     */
    static void awaitAck(CompletableFuture<Void> ack, String description) {
        awaitAck(ack, description, ACK_TIMEOUT);
    }

    /**
     * Package-private, with the timeout a parameter, because this method had NO test.
     *
     * <p>A review proved the gap by mutation: rewriting the timeout branch below to
     * {@code BrokerAckFailure.rejected(...)} classifies an elapsed wait as a definite miss — which is
     * verbatim the duplicate-run behaviour this whole change exists to remove — and all 949 tests in
     * this module still passed. Everything above tested the two factory methods directly, and
     * everything below mocked the emitter and threw a ready-made failure; nothing crossed the seam
     * where the classification is actually chosen.
     *
     * <p>The timeout is injected rather than the constant read, so a test can drive the elapsed-wait
     * branch in milliseconds instead of ten seconds.
     */
    static void awaitAck(CompletableFuture<Void> ack, String description, Duration timeout) {
        try {
            ack.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BrokerAckFailure.notAcknowledged("Interrupted awaiting broker ack for " + description, e);
        } catch (ExecutionException e) {
            throw BrokerAckFailure.rejected(description, e.getCause());
        } catch (TimeoutException e) {
            throw BrokerAckFailure.notAcknowledged(
                    "No broker ack within " + timeout.toSeconds() + "s for " + description, e);
        }
    }
}
