package dev.codespire.orchestrator.web;

import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ScmApiException;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.pipeline.ReviewRerunService;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.readmodel.ReviewDetail;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewSummary;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/** The reviews read API backing spire-ui (list + per-PR detail). */
@Path("/api/reviews")
@RolesAllowed({"spire-viewer", "spire-admin"})
@Produces(MediaType.APPLICATION_JSON)
public class ReviewsResource {

    @Inject
    ReviewProjection projection;

    @Inject
    ReviewRerunService rerunService;

    @Inject
    ReviewProviderResolver reviewProviders;

    @Inject
    ProviderClients clients;

    @GET
    public List<ReviewSummary> list(@QueryParam("includeArchived") @DefaultValue("false")
                                    boolean includeArchived) {
        return projection.listSummaries(includeArchived);
    }

    /**
     * Re-fetch a finding's conversation thread from the SCM in full (ADR-011: conversation text is
     * never persisted — only the ≤160-char preview is stored, the full messages live on the SCM).
     * The UI calls this when a finding's conversation is expanded; on any failure it falls back to the
     * stored preview. {@code threadRef} is the SCM comment/discussion id the finding owns.
     */
    @GET
    @Path("/{workspace}/{slug}/{pr}/threads/{threadRef}")
    public List<ThreadMessage> thread(@PathParam("workspace") String workspace,
                                      @PathParam("slug") String slug,
                                      @PathParam("pr") long pr,
                                      @PathParam("threadRef") String threadRef) {
        // Resolve by the review's stored SCM type — NOT workspace alone, which picks the oldest
        // provider and cross-wires SCMs when a workspace name is shared (e.g. a GitHub org and a
        // Bitbucket workspace both named "acme"), calling the wrong API and 404-ing.
        String reviewId = ReviewIds.reviewId(new RepoRef(workspace, slug), pr);
        ScmProvider provider = reviewProviders.resolveForReview(reviewId)
                .orElseThrow(() -> new NotFoundException("No provider registered for workspace " + workspace));
        ThreadSource source;
        try {
            source = clients.threadSource(provider);
        } catch (UnsupportedOperationException e) {
            // Provider's comment sink doesn't implement ThreadSource — the UI keeps the preview.
            throw new WebApplicationException(e.getMessage(), Response.Status.NOT_IMPLEMENTED);
        }
        try {
            return source.fetchThread(new RepoRef(workspace, slug), pr, new ThreadRef(threadRef)).messages();
        } catch (RuntimeException e) {
            // A genuine SCM failure (deleted PR, transient, mis-scoped token) must not 500 the detail
            // view — the UI falls back to the stored preview on any non-2xx. Non-SCM bugs still surface.
            if (e instanceof ScmApiException) {
                throw new WebApplicationException("Thread re-fetch failed: " + e.getMessage(),
                        Response.Status.BAD_GATEWAY);
            }
            throw e;
        }
    }

    @GET
    @Path("/{workspace}/{slug}/{pr}")
    public ReviewDetail detail(@PathParam("workspace") String workspace,
                               @PathParam("slug") String slug,
                               @PathParam("pr") long pr) {
        return projection.loadDetail(workspace, slug, pr)
                .orElseThrow(() -> new NotFoundException("No review for " + workspace + "/" + slug + "#" + pr));
    }

    /** The pull request's description as it stands NOW — fetched live, never stored. */
    public record DescriptionView(String description) {
    }

    /**
     * Fetched live rather than stored: the description lives on neither {@code review_status} nor
     * {@code DiffFetched}, so the UI always shows the PR's current text, not what the review parsed.
     */
    @GET
    @Path("/{workspace}/{slug}/{pr}/description")
    public DescriptionView description(@PathParam("workspace") String workspace,
                                       @PathParam("slug") String slug,
                                       @PathParam("pr") long pr) {
        RepoRef repo = new RepoRef(workspace, slug);
        // NotFoundException(String) only sets the exception's own message, not the HTTP response
        // body — the entity must be set explicitly so the operator sees why the fetch was refused.
        ScmProvider provider = reviewProviders.resolveForReview(ReviewIds.reviewId(repo, pr))
                .orElseThrow(() -> new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                        .entity("No enabled provider for " + workspace + "/" + slug).build()));
        try {
            return new DescriptionView(clients.diffSource(provider).fetchPullRequest(repo, pr).description());
        } catch (RuntimeException e) {
            // Provider-neutral: every adapter implements ScmApiException, so one platform's status
            // codes are never interpreted here. A genuine bug still surfaces unchanged.
            if (!(e instanceof ScmApiException api)) {
                throw e;
            }
            if (api.isNotFound()) {
                throw new NotFoundException("Pull request not found: " + workspace + "/" + slug + "#" + pr);
            }
            throw new ServiceUnavailableException(
                    api.isUnauthorized() ? "The stored credential was rejected." : "Could not reach the provider.");
        }
    }

    /** Re-run a review's pipeline on its stored commit (force restart; clears cached LLM result). */
    @POST
    @RolesAllowed("spire-admin")
    @Path("/{workspace}/{slug}/{pr}/rerun")
    public Map<String, Object> rerun(@PathParam("workspace") String workspace,
                                     @PathParam("slug") String slug,
                                     @PathParam("pr") long pr) {
        boolean started = rerunService.rerun(workspace, slug, pr);
        return Map.of("reviewId", ReviewIds.reviewId(new RepoRef(workspace, slug), pr), "started", started);
    }

    /**
     * Acknowledge a stuck or failed review so the attention panel stops raising a row for it.
     *
     * <p>The panel's other rows are not dismissable, because each describes a condition an operator
     * can fix — silencing one would let a broken system look healthy. A review that already failed
     * is different: it stays failed, so acknowledging is the only resolution there is.
     *
     * <p>Scoped to the state the operator saw, not to the review forever: a later failure on the
     * same review raises the row again.
     */
    @POST
    // Viewer: dismisses an attention row about a review that already failed. It changes what the
    // panel shows, not what the system does, and the next failure raises the row again.
    @Path("/{workspace}/{slug}/{pr}/attention-ack")
    @Consumes(MediaType.WILDCARD) // no request body
    public Response acknowledgeAttention(@PathParam("workspace") String workspace,
                                         @PathParam("slug") String slug,
                                         @PathParam("pr") long pr) {
        if (projection.loadDetail(workspace, slug, pr).isEmpty()) {
            throw new NotFoundException("No review for " + workspace + "/" + slug + "#" + pr);
        }
        projection.acknowledgeAttention(ReviewIds.reviewId(new RepoRef(workspace, slug), pr));
        return Response.noContent().build();
    }

    /**
     * Archive a review. Not a DELETE: nothing is destroyed, and a DELETE verb that destroys nothing
     * misdescribes the operation to every future reader of this API.
     */
    @POST
    @RolesAllowed("spire-admin")
    @Path("/{workspace}/{slug}/{pr}/archive")
    @Consumes(MediaType.WILDCARD) // no request body
    public Response archive(@PathParam("workspace") String workspace,
                            @PathParam("slug") String slug,
                            @PathParam("pr") long pr) {
        return switch (projection.archiveReview(workspace, slug, pr)) {
            case ARCHIVED -> Response.noContent().build();
            case ALREADY_ARCHIVED -> throw conflict("This review is already archived.");
            case STILL_RUNNING -> throw conflict("This review is still running. "
                    + "Wait for it to finish, or cancel it, then archive.");
            case NOT_FOUND -> throw new NotFoundException(
                    "No review for " + workspace + "/" + slug + "#" + pr);
        };
    }

    @POST
    @RolesAllowed("spire-admin")
    @Path("/{workspace}/{slug}/{pr}/unarchive")
    @Consumes(MediaType.WILDCARD) // no request body
    public Response unarchive(@PathParam("workspace") String workspace,
                              @PathParam("slug") String slug,
                              @PathParam("pr") long pr) {
        if (!projection.unarchiveReview(workspace, slug, pr)) {
            throw new NotFoundException("No archived review for " + workspace + "/" + slug + "#" + pr);
        }
        // Re-arm the one-time notice, so a later re-archive can announce itself again.
        projection.releaseArchivedNoticeClaim(ReviewIds.reviewId(new RepoRef(workspace, slug), pr));
        return Response.noContent().build();
    }

    /**
     * A ClientErrorException built from a bare string sets the EXCEPTION's message, not the response
     * entity, so the sentence explaining what to do never reaches the client. Build the response.
     */
    private static ClientErrorException conflict(String message) {
        return new ClientErrorException(
                Response.status(Response.Status.CONFLICT).entity(message).build());
    }
}
