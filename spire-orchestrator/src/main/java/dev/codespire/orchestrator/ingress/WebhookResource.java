package dev.codespire.orchestrator.ingress;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.port.ScmIngress;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The ONE synchronous edge (ARCHITECTURE §3): verify the webhook signature,
 * translate to integration events, emit, return 202. Never processes inline.
 * At the P1+ service split this endpoint moves to spire-gateway unchanged.
 */
@Path("/webhooks/bitbucket")
public class WebhookResource {

    private static final Logger LOG = Logger.getLogger(WebhookResource.class);

    @Inject
    ScmIngress ingress;

    @Inject
    @Channel("integration")
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
        events.forEach(integration::send);
        return Response.accepted().build();
    }
}
