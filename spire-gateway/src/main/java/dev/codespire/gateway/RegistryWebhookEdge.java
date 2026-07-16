package dev.codespire.gateway;

import dev.codespire.contract.event.EventKeys;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.event.IntegrationEvent.PullRequestClosed;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.event.IntegrationEvent.PushReceived;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.port.ScmIngress;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.gateway.registry.WebhookRepoRegistry;
import dev.codespire.gateway.registry.WebhookRepoRegistry.Resolved;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The shared per-repo webhook edge used by every registry-routed provider (GitHub,
 * GitLab). The {@code {key}} path segment resolves a single {@code webhook_repo} row
 * (its decrypted per-repo secret + expected repo scope); the edge builds the
 * provider's ingress from that secret, verifies, translates, cross-checks the
 * payload's repo against the registration scope, and publishes to cs.integration
 * keyed by reviewId.
 *
 * <p>Only the verify + translate step is provider-specific — the resolve → verify →
 * translate → scope → publish tail is identical, so provider resources stay thin:
 * they supply the provider type and a factory that turns the secret into the right
 * {@link ScmIngress}. This is the same tail the Bitbucket edge runs inline, so the
 * whole downstream saga is unchanged.
 */
@ApplicationScoped
public class RegistryWebhookEdge {

    private static final Logger LOG = Logger.getLogger(RegistryWebhookEdge.class);

    @Inject
    WebhookRepoRegistry registry;

    @Inject
    IntegrationPublisher publisher;

    /** Builds the provider ingress from the decrypted per-repo secret — the only per-provider difference. */
    public interface IngressFactory extends Function<String, ScmIngress> {
    }

    public Response handle(String providerType, String key, IngressFactory ingressFactory,
                           HttpHeaders headers, byte[] body) {
        MDC.put("provider", providerType);
        try {
            return route(providerType, key, ingressFactory, headers, body);
        } finally {
            MDC.remove("provider");
            MDC.remove("reviewId");
        }
    }

    private Response route(String providerType, String key, IngressFactory ingressFactory,
                           HttpHeaders headers, byte[] body) {
        Optional<Resolved> found = registry.findByKey(key);
        if (found.isEmpty()) {
            // Unknown or disabled key — reveal nothing (no verification attempted).
            LOG.warnf("Rejected %s webhook for an unknown/disabled key", providerType);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Resolved repo = found.get();
        if (!providerType.equals(repo.providerType())) {
            LOG.warnf("Webhook key is registered for provider type %s, not %s", repo.providerType(), providerType);
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        RawWebhook raw = rawFrom(headers, body);
        ScmIngress ingress = ingressFactory.apply(repo.secret());
        if (!ingress.verifySignature(raw)) {
            LOG.warnf("Rejected %s webhook with missing/invalid signature", providerType);
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        List<IntegrationEvent> events;
        try {
            events = ingress.translate(raw);
        } catch (RuntimeException e) {
            // Authenticated but malformed/invalid payload — client error, not a 500.
            LOG.warnf(e, "%s webhook payload rejected", providerType);
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        if (events.isEmpty()) {
            // ping / uninteresting action — accepted, nothing to publish.
            return Response.noContent().build();
        }

        // The key is bound to a specific repo (repo scope) or a whole org/group (org
        // scope). EVERY emitted event must fall inside that scope — a payload outside it
        // means a misconfigured or spoofed hook. Fail closed: an event whose repo cannot
        // be determined (an unmapped type) is refused, not waved through.
        for (IntegrationEvent event : events) {
            RepoRef eventRepo = repoOf(event);
            if (eventRepo != null && inScope(repo, eventRepo)) {
                continue;
            }
            LOG.warnf("%s webhook event '%s' (repo %s) is outside the registered %s scope '%s'",
                    providerType, event.getClass().getSimpleName(),
                    eventRepo == null ? "?" : eventRepo.full(), repo.scope(), repo.target());
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

    /** repo scope → exact owner/repo; org scope → any repo whose top group/owner matches the registration. */
    private static boolean inScope(Resolved reg, RepoRef eventRepo) {
        return "org".equals(reg.scope())
                ? eventRepo.workspace().equals(reg.target())
                : eventRepo.full().equals(reg.target());
    }

    /**
     * The repo an ingress attaches to each event it emits — enumerated over EVERY
     * ingress-produced event type (all carry a repo). {@code null} for anything else,
     * which the caller treats as out-of-scope (fail closed), so a new repo-bearing
     * event type must be added here to ever pass the scope check.
     */
    private static RepoRef repoOf(IntegrationEvent e) {
        return switch (e) {
            case PullRequestEventReceived p -> p.repo();
            case PullRequestClosed p -> p.repo();
            case ManualCommandReceived p -> p.repo();
            case AuthorReplied p -> p.repo(); // Bitbucket emits comment replies
            case PushReceived p -> p.repo();  // future push hooks — scope-guard them too
            default -> null;
        };
    }
}
