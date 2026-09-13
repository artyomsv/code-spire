package dev.codespire.orchestrator.ingress;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ScmApiException;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
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
 * cs.repository-integration with the selected repository's provenance, so
 * the allowlist, observe-mode and read-model registration all apply unchanged.
 */
@Path("/api/reviews/register")
@RolesAllowed({"spire-viewer", "spire-admin"})
public class ManualRegisterResource {

    private static final Logger LOG = Logger.getLogger(ManualRegisterResource.class);

    @Inject
    dev.codespire.orchestrator.repository.RepositoryAccounts accounts;

    @Inject
    dev.codespire.orchestrator.repository.RepositoryRegistry repositories;

    @Inject
    ProviderClients clients;

    @Inject
    PrUrlParsers urlParsers;

    @Inject
    dev.codespire.orchestrator.repository.RepositoryDeliveryEmitter integration;

    @Inject
    ReviewProjection projection;

    /**
     * Either {@code url}, resolved by full forge identity, or {@code repositoryId} + {@code pr}.
     * Optional coordinates must agree with the selected repository.
     */
    public record RegisterRequest(String url, String workspace, String slug, Long pr, String providerType, java.util.UUID repositoryId) {
    }

    @POST
    @RolesAllowed("spire-admin")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> register(RegisterRequest req) {
        Target target = resolve(req);
        String requestedReviewId = ReviewIds.reviewId(new RepoRef(target.workspace, target.slug), target.pr);
        RepoRef repo = ReviewIds.parse(requestedReviewId).repo();
        if (projection.archived(requestedReviewId)) {
            throw new ClientErrorException(Response.status(409)
                    .entity("This pull request's review is archived. Unarchive it to review again.").build());
        }
        if (projection.registered(requestedReviewId)
                && !projection.repositoryIdOf(requestedReviewId).filter(id -> id.equals(target.repositoryId)).isPresent()) {
            throw new ClientErrorException(Response.status(409)
                    .entity("This review has no matching repository mapping. Repair its mapping before dispatch.").build());
        }

        // The repository's explicit reviewer binding selects the credential and adapter.
        ScmProvider provider = resolveProvider(target)
                .orElseThrow(() -> new NotFoundException("No usable Reviewer account is selected for repository "
                        + target.workspace + "/" + target.slug
                        + ". Register and enable the repository, then select an enabled Reviewer under Settings -> Repositories."));
        DiffSource diffSource = clients.diffSource(provider);

        PullRequest pr;
        try {
            pr = diffSource.fetchPullRequest(repo, target.pr);
        } catch (RuntimeException e) {
            // ScmApiException is the provider-neutral shape every adapter implements. This
            // used to catch one adapter's exception class, so a GitHub or GitLab PR that
            // 404'd escaped as a 500 instead of the "not found" the operator needs. Genuine
            // (non-SCM) bugs still surface unchanged.
            if (!(e instanceof ScmApiException api)) {
                throw e;
            }
            if (api.isNotFound()) {
                throw new NotFoundException("Pull request not found: "
                        + target.workspace + "/" + target.slug + "#" + target.pr);
            }
            // Generic message to the client — upstream status/detail stays server-side.
            LOG.warnf(e, "SCM fetch failed for %s/%s#%d via provider %s: status %d",
                    target.workspace, target.slug, target.pr, provider.id(), api.status());
            throw new WebApplicationException("Could not fetch the pull request from the provider. "
                    + "Check the repo, PR number, and bot credentials.", Response.Status.BAD_GATEWAY);
        }

        String reviewId = ReviewIds.reviewId(repo, pr.prId());
        // The saga would drop this event for an archived review, but silently: the caller would get a
        // 200 with a reviewId and nothing would happen. A silent non-response reads as a lost webhook,
        // which this project already had to fix once for the conversation turn cap.
        if (projection.archived(reviewId)) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity("This pull request's review is archived. Unarchive it to review again.")
                    .build());
        }

        IntegrationEvent event = new PullRequestEventReceived(
                pr.repo(), pr.prId(), PrAction.OPENED, pr.title(), pr.description(),
                pr.sourceBranch(), pr.targetBranch(), pr.headCommit(), pr.author(), pr.htmlUrl(),
                provider.type());
        integration.send(new dev.codespire.contract.event.RepositoryDelivery(target.repositoryId, null, 0,
                target.providerType, target.forgeOrigin, dev.codespire.contract.event.RepositoryEventKind.REVIEWER,
                java.util.UUID.randomUUID().toString(), event));

        LOG.infof("Manually registered %s (author @%s)", reviewId,
                pr.author() == null ? "unknown" : pr.author().username());
        return Map.of("reviewId", reviewId, "workspace", target.workspace,
                "slug", target.slug, "pr", pr.prId());
    }

    /** Full forge identity from the parsed URL or explicitly selected repository. */
    private record Target(String workspace, String slug, long pr, String providerType, String forgeOrigin, java.util.UUID repositoryId) {
    }

    /** The selected repository's usable reviewer account. */
    private Optional<ScmProvider> resolveProvider(Target target) {
        return Optional.ofNullable(target.repositoryId)
                .flatMap(id -> accounts.resolve(id, dev.codespire.orchestrator.provider.ProviderRole.REVIEWER));
    }

    /** Request + result of the URL-preview endpoint (parse without registering). */
    public record ResolveRequest(String url) {
    }

    public record ResolvedUrl(String workspace, String slug, long pr,
                              boolean providerRegistered, String providerType, String providerName, java.util.UUID repositoryId, String forgeOrigin) {
    }

    /**
     * Parse a pull-request / merge-request URL into its fields WITHOUT registering,
     * and report which registered provider (if any) would handle it — so the UI can
     * fill the form and confirm a provider is set up, using the single backend
     * parser instead of duplicating the URL regexes client-side.
     */
    @POST
    // Viewer: parses a pasted URL and reports which provider would handle it. No side effect, no
    // spend -- an operator who may look at reviews may work out which one a URL refers to.
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
                provider.map(ScmProvider::name).orElse(null), target.repositoryId, target.forgeOrigin);
    }

    private Target resolve(RegisterRequest req) {
        if (req != null && req.url() != null && !req.url().isBlank()) {
            return parseUrl(req.url().trim());
        }
        if (req == null || req.repositoryId() == null || req.pr() == null || req.pr() < 1) {
            throw new BadRequestException("Provide a pull request URL, or repositoryId and a positive pr number.");
        }
        var repository = repositories.get(req.repositoryId())
                .orElseThrow(() -> new NotFoundException("Repository is not registered"));
        if ((req.workspace() != null && !req.workspace().equals(repository.workspace()))
                || (req.slug() != null && !req.slug().equals(repository.slug()))
                || (req.providerType() != null && !req.providerType().equals(repository.scmType()))) {
            throw new BadRequestException("Request coordinates do not match the selected repository");
        }
        return new Target(repository.workspace(), repository.slug(), req.pr(), repository.scmType(),
                repository.forgeOrigin(), repository.id());
    }

    /** Delegate URL parsing to the per-provider parsers; the first match wins and names the SCM type. */
    private Target parseUrl(String url) {
        PrUrlParsers.Match match = urlParsers.parse(url)
                .orElseThrow(() -> new BadRequestException("Unrecognised pull request URL"));
        String origin = ProviderClients.repositoryForgeOrigin(match.type().providerType(), url);
        RepoRef repo = match.coordinates().repo();
        java.util.UUID repositoryId = repositories.find(match.type().providerType(), origin, repo.workspace(), repo.slug())
                .map(dev.codespire.orchestrator.repository.RepositoryView::id).orElse(null);
        return new Target(repo.workspace(), repo.slug(), match.coordinates().prId(), match.type().providerType(), origin, repositoryId);
    }
}
