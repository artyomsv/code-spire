package dev.codespire.worker.pipeline;

import dev.codespire.contract.event.EventKeys;
import dev.codespire.contract.event.IntegrationEvent;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes worker results to cs.results, keyed by reviewId (CONTRACT §9).
 * The send AWAITS the broker ack (mirrors the gateway's webhook publish): a
 * publish failure throws inside the @Incoming processing, so the incoming
 * command is nacked and lands on cs.dlq instead of vanishing with the review
 * stuck forever.
 */
@ApplicationScoped
public class ResultsEmitter {

    private static final Logger LOG = Logger.getLogger(ResultsEmitter.class);
    private static final long ACK_TIMEOUT_SECONDS = 10;

    @Inject
    @Channel("results-out")
    Emitter<IntegrationEvent> results;

    public void emit(IntegrationEvent event) {
        var acked = new CompletableFuture<Void>();
        results.send(Message.of(event,
                Metadata.of(OutgoingKafkaRecordMetadata.<String>builder()
                        .withKey(EventKeys.of(event)).build()),
                () -> {
                    acked.complete(null);
                    return CompletableFuture.<Void>completedFuture(null);
                },
                failure -> {
                    acked.completeExceptionally(failure);
                    return CompletableFuture.<Void>completedFuture(null);
                }));
        try {
            acked.get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            LOG.errorf(e.getCause(), "Failed to emit %s", event.getClass().getSimpleName());
            throw new CompletionException(e.getCause());
        } catch (TimeoutException e) {
            LOG.errorf(e, "Timed out awaiting broker ack for %s", event.getClass().getSimpleName());
            throw new CompletionException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }
}
