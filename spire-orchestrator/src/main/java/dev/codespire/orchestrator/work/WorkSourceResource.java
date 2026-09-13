package dev.codespire.orchestrator.work;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;

@Path("/api/work-sources") @RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class WorkSourceResource {
    @Inject WorkSourceRegistry registry;
    @Inject WorkSourceAdministration administration;
    @Inject WorkSourceScanner scanner;
    @GET public List<WorkSourceRegistry.Source> list() { return registry.list(); }
    @POST public Response create(WorkSourceAdministration.Input input) {
        try { return Response.status(201).entity(administration.create(input)).build(); }
        catch (IllegalArgumentException invalid) { throw new BadRequestException(invalid.getMessage()); }
    }
    @PUT @Path("/{id}") public WorkSourceRegistry.Source edit(@PathParam("id") UUID id, WorkSourceAdministration.Edit input) {
        try { return administration.edit(id, input); }
        catch (IllegalArgumentException invalid) { throw new BadRequestException(invalid.getMessage()); }
    }
    @POST @Path("/{id}/actors") public WorkSourceRegistry.Source actor(@PathParam("id") UUID id, WorkSourceAdministration.ActorInput input) {
        try { return administration.saveActor(id, input); }
        catch (IllegalArgumentException invalid) { throw new BadRequestException(invalid.getMessage()); }
    }
    @DELETE @Path("/{id}/actors/{actor}") public WorkSourceRegistry.Source remove(@PathParam("id") UUID id,
            @PathParam("actor") String actor, @QueryParam("revision") long revision) {
        try { return administration.removeActor(id, actor, revision); }
        catch (IllegalArgumentException invalid) { throw new BadRequestException(invalid.getMessage()); }
    }
    @POST @Path("/{id}/rescan") public Response rescan(@PathParam("id") UUID id) {
        scanner.request(id); return Response.accepted().build();
    }
}
