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

/** Publishes worker results to cs.results, keyed by reviewId (CONTRACT §9). */
@ApplicationScoped
public class ResultsEmitter {

    private static final Logger LOG = Logger.getLogger(ResultsEmitter.class);

    @Inject
    @Channel("results-out")
    Emitter<IntegrationEvent> results;

    public void emit(IntegrationEvent event) {
        results.send(Message.of(event,
                Metadata.of(OutgoingKafkaRecordMetadata.<String>builder()
                        .withKey(EventKeys.of(event)).build()),
                () -> java.util.concurrent.CompletableFuture.<Void>completedFuture(null),
                failure -> {
                    LOG.warnf(failure, "Failed to emit %s", event.getClass().getSimpleName());
                    return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
                }));
    }
}
