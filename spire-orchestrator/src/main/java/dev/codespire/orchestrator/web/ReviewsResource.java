package dev.codespire.orchestrator.web;

import dev.codespire.orchestrator.readmodel.ReviewDetail;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** The reviews read API backing spire-ui (list + per-PR detail). */
@Path("/api/reviews")
@Produces(MediaType.APPLICATION_JSON)
public class ReviewsResource {

    @Inject
    ReviewProjection projection;

    @GET
    public List<ReviewSummary> list() {
        return projection.listSummaries();
    }

    @GET
    @Path("/{workspace}/{slug}/{pr}")
    public ReviewDetail detail(@PathParam("workspace") String workspace,
                               @PathParam("slug") String slug,
                               @PathParam("pr") long pr) {
        return projection.loadDetail(workspace, slug, pr)
                .orElseThrow(() -> new NotFoundException("No review for " + workspace + "/" + slug + "#" + pr));
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
