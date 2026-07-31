package dev.codespire.worker.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.review.AssembledContext;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.worker.adapters.PostgresBlobStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * The assembled context a review was given, for the review detail page.
 *
 * <p>The worker serves this rather than the orchestrator reading across schemas: the blob is this
 * service's data, encrypted with its keyset under its own AAD convention, and only this service
 * can address it (a review's contextRef is never projected). Same shape as the attention panel,
 * where each service answers for its own schema and the UI merges.
 *
 * <p>Read-only and idempotent — nothing here writes, deletes or re-resolves anything.
 */
@Path("/api/review-context")
@Produces(MediaType.APPLICATION_JSON)
public class ReviewContextResource {

    private static final Logger LOG = Logger.getLogger(ReviewContextResource.class);
    private static final ReviewContextView EMPTY = new ReviewContextView(List.of(), Set.of(), Set.of());

    @Inject
    PostgresBlobStore blobStore;

    @Inject
    ObjectMapper mapper;

    /** Read-only projection of {@link AssembledContext} — no contextId, which is internal. */
    public record ReviewContextView(List<ContextItem> items,
                                    Set<String> contributingSources,
                                    Set<String> missingSources) {
    }

    @GET
    @Path("/{workspace}/{slug}/{pr}")
    public ReviewContextView get(@PathParam("workspace") String workspace,
                                 @PathParam("slug") String slug,
                                 @PathParam("pr") long pr) {
        String reviewId = ReviewIds.reviewId(new RepoRef(workspace, slug), pr);
        byte[] payload = blobStore.getByReview(reviewId);
        if (payload == null) {
            return EMPTY;
        }
        return view(payload, reviewId);
    }

    private ReviewContextView view(byte[] payload, String reviewId) {
        try {
            AssembledContext context = mapper.readValue(payload, AssembledContext.class);
            return new ReviewContextView(
                    context.items() == null ? List.of() : context.items(),
                    context.contributingSources() == null ? Set.of() : context.contributingSources(),
                    context.missingSources() == null ? Set.of() : context.missingSources());
        } catch (IOException e) {
            // A blob we cannot parse is a display problem, not a reason to fail the page. Log the
            // reviewId (an identifier) but never the payload: context items quote issue/ticket text.
            LOG.warnf(e, "Unreadable context blob for %s", reviewId);
            return EMPTY;
        }
    }
}
