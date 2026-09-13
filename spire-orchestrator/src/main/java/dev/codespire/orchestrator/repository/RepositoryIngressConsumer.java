package dev.codespire.orchestrator.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.EventKeys;
import dev.codespire.contract.event.RepositoryDelivery;
import dev.codespire.contract.event.RepositoryEventKind;
import dev.codespire.orchestrator.pipeline.IntegrationSaga;
import dev.codespire.orchestrator.pipeline.KafkaSends;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/** No synchronous gateway lookup: the signed edge supplies provenance with the event. */
@ApplicationScoped
public class RepositoryIngressConsumer {
    @Inject ObjectMapper mapper;
    @Inject RepositoryRegistry repositories;
    @Inject UnregisteredRepositoryEvents unregistered;
    @Inject IntegrationSaga reviews;
    @Inject @Channel("repository-activity-out") Emitter<RepositoryDelivery> activities;

    @Incoming("repository-in") @Blocking
    public void on(String payload) throws JsonProcessingException {
        accept(mapper.readValue(payload, RepositoryDelivery.class));
    }

    public void accept(RepositoryDelivery delivery) {
        if (!delivery.eventKind().accepts(delivery.event())) return;
        Optional<RepositoryView> selected = delivery.forgeOrigin() == null ? Optional.empty()
                : repositories.find(delivery.providerType(), delivery.forgeOrigin(),
                        delivery.repo().workspace(), delivery.repo().slug());
        if (selected.isEmpty()) {
            unregistered.record(delivery);
            return;
        }
        RepositoryView repository = selected.get();
        if (!repository.enabled() || (delivery.repositoryId() != null
                && !repository.id().equals(delivery.repositoryId()))) return;
        // AuthorReplied carries both a review id and coordinates. Never let those name two targets.
        if (delivery.event() instanceof dev.codespire.contract.event.IntegrationEvent.AuthorReplied reply
                && !dev.codespire.contract.event.ReviewIds.parse(reply.reviewId()).repo().equals(delivery.repo())) return;
        if (delivery.eventKind() == RepositoryEventKind.REVIEWER) {
            reviews.onRepository(delivery.event(), repository.id());
        } else if (delivery.eventKind() == RepositoryEventKind.FACTORY) {
            KafkaSends.sendAndAwait(activities, repository.id().toString(), delivery, "Repository activity");
        }
    }
}
