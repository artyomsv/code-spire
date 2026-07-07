package dev.codespire.orchestrator.ingress;

import dev.codespire.contract.event.EventKeys;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.orchestrator.pipeline.KafkaSends;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

/**
 * Single owner of the {@code integration-out} emitter (cs.integration), keyed by
 * reviewId. Shared by the dev simulator and the manual-register endpoint so only
 * one emitter is bound to the channel.
 */
@ApplicationScoped
public class IntegrationEmitter {

    @Inject
    @Channel("integration-out")
    Emitter<IntegrationEvent> emitter;

    /**
     * Awaits the broker ack: on a publish failure the REST caller gets a 5xx
     * instead of a fake success that registered nothing.
     */
    public void send(IntegrationEvent event) {
        String key = EventKeys.of(event);
        KafkaSends.sendAndAwait(emitter, key, event,
                event.getClass().getSimpleName() + " for " + key);
    }
}
