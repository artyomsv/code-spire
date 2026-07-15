package dev.codespire.orchestrator.ingress;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.scm.bitbucket.BitbucketApiException;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Manually register a pull request for review without a webhook: fetch the PR's
 * metadata from the SCM and emit a {@link PullRequestEventReceived} onto
 * cs.integration — the exact same signal the gateway produces from a webhook, so
 * the allowlist, observe-mode and read-model registration all apply unchanged.
 */
@Path("/api/reviews/register")
public class ManualRegisterResource {

    private static final Logger LOG = Logger.getLogger(ManualRegisterResource.class);

    // A single path segment's charset (same as the webhook ingress guard), used to
    // validate the explicit workspace + slug JSON path. URL parsing is delegated to
    // the per-provider PrUrlParsers, which own their own grammar.
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?");

    @Inject
    ProviderRegistry providers;

    @Inject
    ProviderClients clients;

    @Inject
    PrUrlParsers urlParsers;

    @Inject
    IntegrationEmitter integration;

    /**
     * Either {@code url}, or {@code workspace} + {@code slug} + {@code pr}. When the
     * caller registers by fields (the UI fills them from a resolved URL), it passes
     * {@code providerType} so a workspace name shared across SCMs still resolves the
     * right provider; null/blank falls back to workspace-only resolution.
     */
    public record RegisterRequest(String url, String workspace, String slug, Long pr, String providerType) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> register(RegisterRequest req) {
        Target target = resolve(req);
        RepoRef repo = new RepoRef(target.workspace, target.slug);

        // Resolve the registered provider and use ITS (decrypted) credentials — no
        // .env token needed. When the PR URL named a provider type (bitbucket-cloud |
        // github | gitlab) we resolve by (type, workspace) so a GitHub org and a
        // Bitbucket workspace of the same name don't collide; the provider's own type
        // selects the adapter.
        ScmProvider provider = resolveProvider(target)
                .orElseThrow(() -> new NotFoundException("No enabled provider registered for workspace '"
                        + target.workspace + "'"
                        + (target.providerType == null ? "" : " on " + target.providerType)
                        + ". Add one under Settings -> Providers."));
        DiffSource diffSource = clients.diffSource(provider);

        PullRequest pr;
        try {
            pr = diffSource.fetchPullRequest(repo, target.pr);
        } catch (BitbucketApiException e) {
            if (e.isNotFound()) {
                throw new NotFoundException("Pull request not found: "
                        + target.workspace + "/" + target.slug + "#" + target.pr);
            }
            // Generic message to the client — upstream status/detail stays server-side.
            LOG.warnf(e, "SCM fetch failed for %s/%s#%d via provider %s: status %d",
                    target.workspace, target.slug, target.pr, provider.id(), e.status());
            throw new WebApplicationException("Could not fetch the pull request from the provider. "
                    + "Check the repo, PR number, and bot credentials.", Response.Status.BAD_GATEWAY);
        }

        IntegrationEvent event = new PullRequestEventReceived(
                pr.repo(), pr.prId(), PrAction.OPENED, pr.title(), pr.description(),
                pr.sourceBranch(), pr.targetBranch(), pr.diffRefs(), pr.author(), pr.htmlUrl(),
                provider.type());
        integration.send(event);

        String reviewId = ReviewIds.reviewId(repo, pr.prId());
        LOG.infof("Manually registered %s (author @%s)", reviewId,
                pr.author() == null ? "unknown" : pr.author().username());
        return Map.of("reviewId", reviewId, "workspace", target.workspace,
                "slug", target.slug, "pr", pr.prId());
    }

    /** {@code providerType} is set when the target came from a URL (which names the SCM), else null. */
    private record Target(String workspace, String slug, long pr, String providerType) {
    }

    /** The enabled provider for a target — by (type, workspace) when the URL named an SCM, else workspace alone. */
    private Optional<ScmProvider> resolveProvider(Target target) {
        return target.providerType == null
                ? providers.resolveByWorkspace(target.workspace)
                : providers.resolve(target.providerType, target.workspace);
    }

    /** Request + result of the URL-preview endpoint (parse without registering). */
    public record ResolveRequest(String url) {
    }

    public record ResolvedUrl(String workspace, String slug, long pr,
                              boolean providerRegistered, String providerType, String providerName) {
    }

    /**
     * Parse a pull-request / merge-request URL into its fields WITHOUT registering,
     * and report which registered provider (if any) would handle it — so the UI can
     * fill the form and confirm a provider is set up, using the single backend
     * parser instead of duplicating the URL regexes client-side.
     */
    @POST
    @Path("/resolve")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ResolvedUrl resolve(ResolveRequest req) {
        if (req == null || req.url() == null || req.url().isBlank()) {
            throw new BadRequestException("url is required");
        }
        Target target = parseUrl(req.url().trim());
        Optional<ScmProvider> provider = resolveProvider(target);
        return new ResolvedUrl(target.workspace, target.slug, target.pr,
                provider.isPresent(),
                provider.map(ScmProvider::type).orElse(null),
                provider.map(ScmProvider::name).orElse(null));
    }

    private Target resolve(RegisterRequest req) {
        if (req != null && req.url() != null && !req.url().isBlank()) {
            return parseUrl(req.url().trim());
        }
        if (req == null || req.workspace() == null || req.slug() == null || req.pr() == null) {
            throw new BadRequestException("Provide a pull request 'url', or 'workspace' + 'slug' + 'pr'.");
        }
        return validated(req.workspace().trim(), req.slug().trim(), req.pr(), req.providerType());
    }

    /** Delegate URL parsing to the per-provider parsers; the first match wins and names the SCM type. */
    private Target parseUrl(String url) {
        return urlParsers.parse(url)
                .map(m -> new Target(m.coordinates().repo().workspace(), m.coordinates().repo().slug(),
                        m.coordinates().prId(), m.type().providerType()))
                .orElseThrow(() -> new BadRequestException("Unrecognised pull request URL — expected "
                        + ".../pull-requests/<id>, .../pull/<id>, or .../-/merge_requests/<id>"));
    }

    private Target validated(String workspace, String slug, long pr, String providerType) {
        if (!SLUG.matcher(workspace).matches() || !validSlug(slug)) {
            throw new BadRequestException("Invalid workspace or repository slug.");
        }
        if (pr <= 0) {
            throw new BadRequestException("Pull request number must be positive.");
        }
        // A blank type (hand-typed fields with no resolved URL) means workspace-only resolution.
        String type = providerType == null || providerType.isBlank() ? null : providerType;
        return new Target(workspace, slug, pr, type);
    }

    /** A repo slug is one or more path segments (GitLab nested groups); each must be a valid segment. */
    private static boolean validSlug(String slug) {
        if (slug.isBlank()) {
            return false;
        }
        for (String segment : slug.split("/", -1)) {
            if (!SLUG.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }
}
