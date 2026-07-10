package dev.codespire.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.EventKeys;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.event.IntegrationEvent.PullRequestClosed;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.gateway.registry.WebhookRepoRegistry;
import dev.codespire.gateway.registry.WebhookRepoRegistry.Resolved;
import dev.codespire.scm.github.GitHubIngress;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * GitHub webhook edge: one endpoint for every registered repository, routed by
 * the {@code {key}} path segment. The key resolves a single {@code webhook_repo}
 * row (its decrypted per-repo HMAC secret + expected repo); the gateway verifies
 * the signature, translates, cross-checks the payload's repo against the
 * registration, and publishes to cs.integration keyed by reviewId — identical
 * tail to the Bitbucket edge, so the whole downstream saga is unchanged.
 */
@Path("/webhooks/github/{key}")
public class GitHubWebhookResource {

    private static final Logger LOG = Logger.getLogger(GitHubWebhookResource.class);

    /** Registered manual commands (CONTRACT §10); mirrors the Bitbucket edge. */
    private static final Set<String> COMMANDS = Set.of("review");

    @Inject
    ObjectMapper mapper;

    @Inject
    WebhookRepoRegistry registry;

    @Inject
    IntegrationPublisher publisher;

    @POST
    public Response receive(@PathParam("key") String key, @Context HttpHeaders headers, byte[] body) {
        MDC.put("provider", "github");
        try {
            return handle(key, headers, body);
        } finally {
            MDC.remove("provider");
            MDC.remove("reviewId");
        }
    }

    private Response handle(String key, HttpHeaders headers, byte[] body) {
        Optional<Resolved> found = registry.findByKey(key);
        if (found.isEmpty()) {
            // Unknown or disabled key — reveal nothing (no HMAC attempted).
            LOG.warn("Rejected github webhook for an unknown/disabled key");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Resolved repo = found.get();
        if (!"github".equals(repo.providerType())) {
            LOG.warnf("Webhook key is registered for provider type %s, not github", repo.providerType());
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        RawWebhook raw = rawFrom(headers, body);
        GitHubIngress ingress = new GitHubIngress(repo.secret(), mapper, COMMANDS);
        if (!ingress.verifySignature(raw)) {
            LOG.warn("Rejected github webhook with missing/invalid signature");
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        List<IntegrationEvent> events;
        try {
            events = ingress.translate(raw);
        } catch (RuntimeException e) {
            // Authenticated but malformed/invalid payload — client error, not a 500.
            LOG.warnf(e, "Github webhook payload rejected");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        if (events.isEmpty()) {
            // ping or an uninteresting action — accepted, nothing to publish.
            return Response.noContent().build();
        }

        // The key is bound to a specific repo (repo scope) or a whole org (org scope).
        // A payload outside that scope means a misconfigured hook — refuse it.
        RepoRef eventRepo = repoOf(events.getFirst());
        if (eventRepo != null && !inScope(repo, eventRepo)) {
            LOG.warnf("Github webhook repo '%s' is outside the registered %s scope '%s'",
                    eventRepo.full(), repo.scope(), repo.target());
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        MDC.put("reviewId", EventKeys.of(events.getFirst()));
        return publisher.publishAllAwait(events) ? Response.accepted().build() : Response.serverError().build();
    }

    private static RawWebhook rawFrom(HttpHeaders headers, byte[] body) {
        Map<String, String> headerMap = new HashMap<>();
        headers.getRequestHeaders().forEach((name, values) ->
                headerMap.put(name, values.isEmpty() ? "" : values.getFirst()));
        return new RawWebhook(headerMap, body);
    }

    /** repo scope → exact owner/repo; org scope → any repo whose owner matches the registered org. */
    private static boolean inScope(Resolved reg, RepoRef eventRepo) {
        return "org".equals(reg.scope())
                ? eventRepo.workspace().equals(reg.target())
                : eventRepo.full().equals(reg.target());
    }

    /** The repo the GitHub ingress attaches to each event it emits (null for any it never emits). */
    private static RepoRef repoOf(IntegrationEvent e) {
        return switch (e) {
            case PullRequestEventReceived p -> p.repo();
            case PullRequestClosed p -> p.repo();
            case ManualCommandReceived p -> p.repo();
            default -> null;
        };
    }
}
