package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.*;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

@ApplicationScoped
public class WorkActivityConsumer {
    @Inject ObjectMapper mapper;
    @Inject RepositoryRegistry repositories;
    @Inject WorkGateChannels gates;
    @Incoming("repository-activity-in") @Blocking
    public void on(String payload)throws com.fasterxml.jackson.core.JsonProcessingException {
        accept(mapper.readValue(payload,RepositoryDelivery.class));
    }
    public void accept(RepositoryDelivery delivery) {
        if(delivery.registrationId()==null || delivery.forgeOrigin()==null || delivery.eventKind()!=RepositoryEventKind.FACTORY
                || !(delivery.event() instanceof IntegrationEvent.RepositoryActivity activity))return;
        var repository=repositories.find(delivery.providerType(),delivery.forgeOrigin(),activity.repo().workspace(),activity.repo().slug()).orElse(null);
        if(repository==null || !repository.enabled() || delivery.repositoryId()!=null && !repository.id().equals(delivery.repositoryId()))return;
        gates.activity(repository.id(),activity,delivery.deliveryId());
    }
}
