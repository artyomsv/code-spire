package dev.codespire.orchestrator.repository;

import dev.codespire.contract.event.EventKeys;
import dev.codespire.contract.event.RepositoryDelivery;
import dev.codespire.orchestrator.pipeline.KafkaSends;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

@ApplicationScoped
public class RepositoryDeliveryEmitter {
    @Inject @Channel("repository-out") Emitter<RepositoryDelivery> emitter;

    public void send(RepositoryDelivery delivery) {
        KafkaSends.sendAndAwait(emitter, EventKeys.of(delivery.event()), delivery, "Repository delivery");
    }
}
