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
import java.util.regex.Matcher;
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

    // workspace / repo slug charset (same as the webhook ingress guard).
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?");
    // {workspace}/{slug}/pull-requests/{id} anywhere in the URL — host-agnostic so
    // proxied hosts (company MCAS: bitbucket.org.mcas.ms) and trailing path/query parse.
    private static final Pattern PR_URL =
            Pattern.compile("([^/\\s?#]+)/([^/\\s?#]+)/(?:pull-requests|pullrequests)/(\\d+)");

    private static final String PROVIDER_TYPE = "bitbucket-cloud";

    @Inject
    ProviderRegistry providers;

    @Inject
    ProviderClients clients;

    @Inject
    IntegrationEmitter integration;

    /** Either {@code url}, or {@code workspace} + {@code slug} + {@code pr}. */
    public record RegisterRequest(String url, String workspace, String slug, Long pr) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> register(RegisterRequest req) {
        Target target = resolve(req);
        RepoRef repo = new RepoRef(target.workspace, target.slug);

        // Resolve the registered provider for this workspace and use ITS
        // (decrypted) credentials — no .env token needed.
        ScmProvider provider = providers.resolve(PROVIDER_TYPE, target.workspace)
                .orElseThrow(() -> new NotFoundException("No enabled provider registered for workspace '"
                        + target.workspace + "'. Add one under Settings -> Providers."));
        DiffSource diffSource = clients.diffSource(provider);

        PullRequest pr;
        try {
            pr = diffSource.fetchPullRequest(repo, target.pr);
        } catch (BitbucketApiException e) {
            if (e.isNotFound()) {
                throw new NotFoundException("Pull request not found: "
                        + target.workspace + "/" + target.slug + "#" + target.pr);
            }
            LOG.warnf("Bitbucket fetch failed for %s/%s#%d: status %d",
                    target.workspace, target.slug, target.pr, e.status());
            throw new WebApplicationException("Could not fetch the pull request from Bitbucket (status "
                    + e.status() + "). Check the repo, PR number, and bot credentials.", Response.Status.BAD_GATEWAY);
        }

        IntegrationEvent event = new PullRequestEventReceived(
                pr.repo(), pr.prId(), PrAction.OPENED, pr.title(), pr.description(),
                pr.sourceBranch(), pr.targetBranch(), pr.diffRefs(), pr.author(), pr.htmlUrl());
        integration.send(event);

        String reviewId = ReviewIds.reviewId(repo, pr.prId());
        LOG.infof("Manually registered %s (author @%s)", reviewId,
                pr.author() == null ? "unknown" : pr.author().username());
        return Map.of("reviewId", reviewId, "workspace", target.workspace,
                "slug", target.slug, "pr", pr.prId());
    }

    private record Target(String workspace, String slug, long pr) {
    }

    private Target resolve(RegisterRequest req) {
        if (req != null && req.url() != null && !req.url().isBlank()) {
            Matcher m = PR_URL.matcher(req.url().trim());
            if (!m.find()) {
                throw new BadRequestException("Unrecognised pull request URL — expected "
                        + ".../<workspace>/<repo>/pull-requests/<id>");
            }
            return validated(m.group(1), m.group(2), Long.parseLong(m.group(3)));
        }
        if (req == null || req.workspace() == null || req.slug() == null || req.pr() == null) {
            throw new BadRequestException("Provide a pull request 'url', or 'workspace' + 'slug' + 'pr'.");
        }
        return validated(req.workspace().trim(), req.slug().trim(), req.pr());
    }

    private Target validated(String workspace, String slug, long pr) {
        if (!SLUG.matcher(workspace).matches() || !SLUG.matcher(slug).matches()) {
            throw new BadRequestException("Invalid workspace or repository slug.");
        }
        if (pr <= 0) {
            throw new BadRequestException("Pull request number must be positive.");
        }
        return new Target(workspace, slug, pr);
    }
}
