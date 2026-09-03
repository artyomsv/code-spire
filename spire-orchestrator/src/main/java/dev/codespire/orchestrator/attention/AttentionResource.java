package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** Conditions the operator should act on, for the spire-ui attention bell. */
@Path("/api/attention")
@RolesAllowed({"spire-viewer", "spire-admin"})
@Produces(MediaType.APPLICATION_JSON)
public class AttentionResource {

    @Inject
    AttentionQueries queries;

    @Inject
    AttentionBroadcaster broadcaster;

    @GET
    public List<AttentionView> list() {
        return queries.collect();
    }

    /**
     * Acknowledge a ledger-wide cost condition, so calls already priced stop being counted.
     *
     * <p>Only the {@link CostAttentionRow} conditions accept this, and an unknown code is a 404
     * rather than a silent success: every other row describes current state, where silencing something
     * repairable would let a broken system look healthy.
     *
     * <p>Viewer, like the per-review {@code attention-ack}: it changes what the panel shows, not what
     * the system does, and a new unpriced call raises the row again.
     */
    @POST
    @Path("/ack/{code}")
    @Consumes(MediaType.WILDCARD) // no request body
    public Response acknowledge(@PathParam("code") String code) {
        CostAttentionRow row = CostAttentionRow.byCode(code)
                .orElseThrow(() -> new NotFoundException("Not an acknowledgeable condition: " + code));
        queries.acknowledge(row);
        // The panel is pushed, not polled, so the row has to be re-evaluated here or the operator sees
        // it linger until the next sweep.
        broadcaster.refresh();
        return Response.noContent().build();
    }
}
