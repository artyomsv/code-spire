package dev.codespire.orchestrator.web;

import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.pipeline.ReviewRerunService;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.readmodel.ReviewDetail;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/** The reviews read API backing spire-ui (list + per-PR detail). */
@Path("/api/reviews")
@Produces(MediaType.APPLICATION_JSON)
public class ReviewsResource {

    @Inject
    ReviewProjection projection;

    @Inject
    ReviewRerunService rerunService;

    @Inject
    ProviderRegistry providers;

    @Inject
    ProviderClients clients;

    @GET
    public List<ReviewSummary> list() {
        return projection.listSummaries();
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
        ScmProvider provider = providers.resolveByWorkspace(workspace)
                .orElseThrow(() -> new NotFoundException("No provider registered for workspace " + workspace));
        ThreadSource source;
        try {
            source = clients.threadSource(provider);
        } catch (UnsupportedOperationException e) {
            // Provider doesn't support thread re-fetch (only GitHub does today) — the UI keeps the preview.
            throw new WebApplicationException(e.getMessage(), Response.Status.NOT_IMPLEMENTED);
        }
        return source.fetchThread(new RepoRef(workspace, slug), pr, new ThreadRef(threadRef)).messages();
    }

    @GET
    @Path("/{workspace}/{slug}/{pr}")
    public ReviewDetail detail(@PathParam("workspace") String workspace,
                               @PathParam("slug") String slug,
                               @PathParam("pr") long pr) {
        return projection.loadDetail(workspace, slug, pr)
                .orElseThrow(() -> new NotFoundException("No review for " + workspace + "/" + slug + "#" + pr));
    }

    /** Re-run a review's pipeline on its stored commit (force restart; clears cached LLM result). */
    @POST
    @Path("/{workspace}/{slug}/{pr}/rerun")
    public Map<String, Object> rerun(@PathParam("workspace") String workspace,
                                     @PathParam("slug") String slug,
                                     @PathParam("pr") long pr) {
        boolean started = rerunService.rerun(workspace, slug, pr);
        return Map.of("reviewId", ReviewIds.reviewId(new RepoRef(workspace, slug), pr), "started", started);
    }

    /** Permanently delete a review and all of its data (row, timeline, event stream). */
    @DELETE
    @Path("/{workspace}/{slug}/{pr}")
    public Response delete(@PathParam("workspace") String workspace,
                           @PathParam("slug") String slug,
                           @PathParam("pr") long pr) {
        if (!projection.deleteReview(workspace, slug, pr)) {
            throw new NotFoundException("No review for " + workspace + "/" + slug + "#" + pr);
        }
        return Response.noContent().build();
    }
}
