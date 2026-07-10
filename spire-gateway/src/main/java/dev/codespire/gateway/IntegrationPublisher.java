package dev.codespire.gateway;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns the single {@code integration-out} emitter and publishes integration
 * events to cs.integration keyed by reviewId, awaiting broker acks before the
 * caller returns its 202 (finding M5): a 2xx that acknowledged an event we
 * failed to publish would lose the webhook — the SCM does not redeliver on 2xx.
 * On failure the caller returns 500 and the SCM retries.
 */
@ApplicationScoped
public class IntegrationPublisher {

    private static final Logger LOG = Logger.getLogger(IntegrationPublisher.class);

    @Inject
    @Channel("integration-out")
    Emitter<IntegrationEvent> integration;

    /** Publish all events, blocking until every broker ack lands. Returns false if any failed. */
    public boolean publishAllAwait(List<IntegrationEvent> events) {
        List<CompletableFuture<Void>> acks = new ArrayList<>();
        for (IntegrationEvent event : events) {
            CompletableFuture<Void> ack = new CompletableFuture<>();
            acks.add(ack);
            integration.send(Message.of(event,
                    Metadata.of(OutgoingKafkaRecordMetadata.<String>builder()
                            .withKey(EventKeys.of(event)).build()),
                    () -> {
                        ack.complete(null);
                        return CompletableFuture.completedFuture(null);
                    },
                    failure -> {
                        ack.completeExceptionally(failure);
                        return CompletableFuture.completedFuture(null);
                    }));
        }
        try {
            CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to publish webhook events to the broker");
            return false;
        }
    }
}
