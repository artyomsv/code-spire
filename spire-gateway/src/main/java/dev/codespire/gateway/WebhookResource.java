package dev.codespire.gateway;

import dev.codespire.contract.event.EventKeys;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.port.ScmIngress;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The ONE synchronous edge (ARCHITECTURE §3): verify the webhook signature,
 * translate to integration events, publish to cs.integration (keyed by
 * reviewId), return 202. Never processes inline.
 */
@Path("/webhooks/bitbucket")
public class WebhookResource {

    private static final Logger LOG = Logger.getLogger(WebhookResource.class);

    @Inject
    ScmIngress ingress;

    @Inject
    @Channel("integration-out")
    Emitter<IntegrationEvent> integration;

    @POST
    public Response receive(@Context HttpHeaders headers, byte[] body) {
        Map<String, String> headerMap = new HashMap<>();
        headers.getRequestHeaders().forEach((name, values) ->
                headerMap.put(name, values.isEmpty() ? "" : values.getFirst()));
        RawWebhook raw = new RawWebhook(headerMap, body);

        if (!ingress.verifySignature(raw)) {
            LOG.warn("Rejected webhook with missing/invalid signature");
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        List<IntegrationEvent> events;
        try {
            events = ingress.translate(raw);
        } catch (RuntimeException e) {
            // Authenticated but malformed/invalid payload — client error, not a 500.
            LOG.warnf(e, "Webhook payload rejected");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        // Await broker acks before the 202 (finding M5): Bitbucket does not
        // redeliver on 2xx, so acknowledging an event we failed to publish
        // would lose the webhook. On failure we return 500 and Bitbucket retries.
        List<java.util.concurrent.CompletableFuture<Void>> acks = new java.util.ArrayList<>();
        for (IntegrationEvent event : events) {
            var ack = new java.util.concurrent.CompletableFuture<Void>();
            acks.add(ack);
            integration.send(Message.of(event,
                    Metadata.of(OutgoingKafkaRecordMetadata.<String>builder()
                            .withKey(EventKeys.of(event)).build()),
                    () -> {
                        ack.complete(null);
                        return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
                    },
                    failure -> {
                        ack.completeExceptionally(failure);
                        return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
                    }));
        }
        try {
            java.util.concurrent.CompletableFuture
                    .allOf(acks.toArray(java.util.concurrent.CompletableFuture[]::new))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to publish webhook events to the broker");
            return Response.serverError().build();
        }
        return Response.accepted().build();
    }
}
