package dev.codespire.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.gateway.RegistryWebhookEdge.IngressFactory;
import dev.codespire.scm.github.GitHubIngress;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Set;

/**
 * GitHub webhook edge: one endpoint for every registered repository, routed by the
 * {@code {key}} path segment. Verification and translation are GitHub-specific
 * (HMAC-SHA256 over the body, {@code pull_request}/{@code issue_comment} events); the
 * resolve → verify → translate → scope → publish tail is shared in
 * {@link RegistryWebhookEdge}.
 */
@Path("/webhooks/github/{key}")
public class GitHubWebhookResource {

    private static final String PROVIDER = "github";

    /** Registered manual commands (CONTRACT §10); mirrors the Bitbucket edge. */
    private static final Set<String> COMMANDS = Set.of("review");

    @Inject
    ObjectMapper mapper;

    @Inject
    RegistryWebhookEdge edge;

    /** Draft-PR policy: default false skips drafts and waits for ready_for_review. */
    @ConfigProperty(name = "spire.review.draft-prs", defaultValue = "false")
    boolean reviewDrafts;

    @POST
    public Response receive(@PathParam("key") String key, @Context HttpHeaders headers, byte[] body) {
        IngressFactory ingress = secret -> new GitHubIngress(secret, mapper, COMMANDS, reviewDrafts);
        return edge.handle(PROVIDER, key, ingress, headers, body);
    }
}
