package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/** Conditions the operator should act on, for the spire-ui attention bell. */
@Path("/api/attention")
@RolesAllowed({"spire-viewer", "spire-admin"})
@Produces(MediaType.APPLICATION_JSON)
public class AttentionResource {

    @Inject
    AttentionQueries queries;

    @GET
    public List<AttentionView> list() {
        return queries.collect();
    }
}
