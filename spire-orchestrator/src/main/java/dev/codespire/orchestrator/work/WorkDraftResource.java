package dev.codespire.orchestrator.work;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** RED: a generated item's draft, for the dashboard (M4 slice 3). */
@Path("/api/work-items/{id}/draft")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
public class WorkDraftResource {
    public record Comment(String state, String ref) {}
    public record Written(String text, String sha256, Comment comment) {}
    public record Revisions(int specification, int plan) {}
    public record View(Written specification, Written plan, Revisions revisions, String ticketLink) {}

    @GET
    public View read(@PathParam("id") String id) { throw new UnsupportedOperationException("RED"); }
}
