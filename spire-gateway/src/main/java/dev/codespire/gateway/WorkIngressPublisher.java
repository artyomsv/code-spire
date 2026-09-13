package dev.codespire.gateway;

import dev.codespire.contract.event.WorkItemIds;
import dev.codespire.contract.event.WorkSourceDelivery;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.gateway.registry.WebhookRepoRegistry.Resolved;
import dev.codespire.worksource.WorkSourceSignal;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Broker acknowledgement is part of accepting a signed delivery. No review event can enter this emitter. */
@ApplicationScoped
public class WorkIngressPublisher {
    @Inject @Channel("work-integration-out") Emitter<WorkSourceDelivery> emitter;

    public boolean publishAwait(Resolved registration, List<WorkSourceSignal> signals, String deliveryId) {
        try {
            for (WorkSourceSignal signal : signals) {
                String[] parts = registration.target().split("/", 2);
                RepoRef repo = new RepoRef(parts[0], parts[1]);
                WorkSourceDelivery delivery = new WorkSourceDelivery(registration.repositoryId(), registration.sourceId(),
                        registration.registrationId(), registration.revision(), registration.providerType(),
                        registration.forgeOrigin(), repo, deliveryId, signal);
                String key = WorkItemIds.of(dev.codespire.contract.port.ScmType.fromProviderType(registration.providerType()).orElseThrow(),
                        registration.forgeOrigin(), repo, signal.issue().ref());
                CompletableFuture<Void> ack = new CompletableFuture<>();
                emitter.send(Message.of(delivery, Metadata.of(OutgoingKafkaRecordMetadata.<String>builder().withKey(key).build()),
                        () -> { ack.complete(null); return CompletableFuture.completedFuture(null); },
                        failure -> { ack.completeExceptionally(failure); return CompletableFuture.completedFuture(null); }));
                ack.get(10, TimeUnit.SECONDS);
            }
            return true;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
