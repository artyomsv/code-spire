package dev.codespire.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.gateway.RegistryWebhookEdge.IngressFactory;
import dev.codespire.scm.gitlab.GitLabIngress;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.util.Set;

/**
 * GitLab webhook edge: one endpoint per registered repository, routed by the
 * {@code {key}} path segment. Verification is a constant-time {@code X-Gitlab-Token}
 * compare (GitLab does not sign the body) and translation handles Merge Request /
 * Note hooks (see {@link GitLabIngress}); the resolve → verify → translate → scope →
 * publish tail is shared in {@link RegistryWebhookEdge}.
 */
@Path("/webhooks/gitlab/{key}")
public class GitLabWebhookResource {

    private static final String PROVIDER = "gitlab";

    /** Registered manual commands (CONTRACT §10); mirrors the other edges. */
    private static final Set<String> COMMANDS = Set.of("review");

    @Inject
    ObjectMapper mapper;

    @Inject
    RegistryWebhookEdge edge;

    @POST
    public Response receive(@PathParam("key") String key, @Context HttpHeaders headers, byte[] body) {
        IngressFactory ingress = secret -> new GitLabIngress(secret, mapper, COMMANDS);
        return edge.handle(PROVIDER, key, ingress, headers, body);
    }
}
