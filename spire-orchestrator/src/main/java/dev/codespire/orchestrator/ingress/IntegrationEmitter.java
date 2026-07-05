package dev.codespire.orchestrator.ingress;

import dev.codespire.contract.event.EventKeys;
import dev.codespire.contract.event.IntegrationEvent;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;

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

    public void send(IntegrationEvent event) {
        emitter.send(Message.of(event, Metadata.of(
                OutgoingKafkaRecordMetadata.<String>builder().withKey(EventKeys.of(event)).build())));
    }
}
