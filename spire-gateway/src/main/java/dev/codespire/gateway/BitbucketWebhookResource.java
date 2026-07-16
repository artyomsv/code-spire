package dev.codespire.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.gateway.RegistryWebhookEdge.IngressFactory;
import dev.codespire.scm.bitbucket.BitbucketCloudConfig;
import dev.codespire.scm.bitbucket.BitbucketCloudIngress;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.util.Set;

/**
 * Bitbucket Cloud webhook edge: one endpoint per registered repository, routed by the
 * {@code {key}} path segment. Verification and translation are Bitbucket-specific
 * (HMAC-SHA256 {@code X-Hub-Signature} over the body, {@code X-Event-Key} pull-request
 * events); the resolve → verify → translate → scope → publish tail is shared in
 * {@link RegistryWebhookEdge}.
 */
@Path("/webhooks/bitbucket-cloud/{key}")
public class BitbucketWebhookResource {

    private static final String PROVIDER = "bitbucket-cloud";

    /** Registered manual commands (CONTRACT §10); mirrors the other edges. */
    private static final Set<String> COMMANDS = Set.of("review");

    /**
     * Bitbucket's API base. The ingress requires a config, but the internet-facing
     * gateway only VERIFIES webhooks — it never calls the API — so the auth fields are
     * placeholders and this base URL is never dialled.
     */
    private static final String API_BASE = "https://api.bitbucket.org/2.0";

    @Inject
    ObjectMapper mapper;

    @Inject
    RegistryWebhookEdge edge;

    @POST
    public Response receive(@PathParam("key") String key, @Context HttpHeaders headers, byte[] body) {
        IngressFactory ingress = secret -> new BitbucketCloudIngress(
                new BitbucketCloudConfig(API_BASE, "unused-by-gateway", "unused-by-gateway", secret),
                mapper, COMMANDS);
        return edge.handle(PROVIDER, key, ingress, headers, body);
    }
}
