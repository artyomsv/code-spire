package dev.codespire.gateway;

import dev.codespire.contract.event.EventKeys;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.RepositoryDelivery;
import dev.codespire.gateway.registry.WebhookRepoRegistry.Resolved;
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
 * Owns the single {@code repository-out} emitter and publishes verified deliveries
 * to cs.repository-integration keyed by reviewId, awaiting broker acks before the
 * caller returns its 202 (finding M5): a 2xx that acknowledged an event we
 * failed to publish would lose the webhook — the SCM does not redeliver on 2xx.
 * On failure the caller returns 500 and the SCM retries.
 */
@ApplicationScoped
public class IntegrationPublisher {

    private static final Logger LOG = Logger.getLogger(IntegrationPublisher.class);

    @Inject
    @Channel("repository-out")
    Emitter<RepositoryDelivery> integration;

    /** Publish all events, blocking until every broker ack lands. Returns false if any failed. */
    public boolean publishAllAwait(Resolved registration, List<IntegrationEvent> events, String deliveryId) {
        List<CompletableFuture<Void>> acks = new ArrayList<>();
        for (IntegrationEvent event : events) {
            CompletableFuture<Void> ack = new CompletableFuture<>();
            acks.add(ack);
            RepositoryDelivery delivery = new RepositoryDelivery(registration.repositoryId(), registration.registrationId(),
                    registration.revision(), registration.providerType(), registration.forgeOrigin(),
                    registration.eventKind(), deliveryId, event);
            integration.send(Message.of(delivery,
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
