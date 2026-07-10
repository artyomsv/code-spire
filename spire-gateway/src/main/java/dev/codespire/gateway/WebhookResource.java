package dev.codespire.gateway;

import dev.codespire.contract.event.EventKeys;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.port.ScmIngress;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

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
    IntegrationPublisher publisher;

    @POST
    public Response receive(@Context HttpHeaders headers, byte[] body) {
        // MDC (observability rule): key identifiers ride on every log line of
        // this request; cleared on the same thread — the handler is synchronous.
        MDC.put("provider", "bitbucket");
        try {
            return handle(headers, body);
        } finally {
            MDC.remove("provider");
            MDC.remove("reviewId");
        }
    }

    private Response handle(HttpHeaders headers, byte[] body) {
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
        if (!events.isEmpty()) {
            MDC.put("reviewId", EventKeys.of(events.getFirst()));
        }
        return publisher.publishAllAwait(events) ? Response.accepted().build() : Response.serverError().build();
    }
}
