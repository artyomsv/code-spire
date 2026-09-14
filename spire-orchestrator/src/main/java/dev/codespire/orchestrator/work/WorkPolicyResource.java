package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkPolicy;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;

@Path("/api/work-policy") @RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class WorkPolicyResource {
    @Inject WorkPolicyRegistry registry;
    @GET @Path("/profiles") public List<WorkPolicy.Profile> profiles() { return registry.profiles(); }
    @POST @Path("/profiles") public Response create(WorkPolicy.Profile profile) {
        try { return Response.status(201).entity(registry.createVersion(profile)).build(); }
        catch (IllegalArgumentException invalid) { throw new BadRequestException(invalid.getMessage()); }
    }
    @GET @Path("/repositories/{id}") public WorkPolicyRegistry.Policy get(@PathParam("id") UUID id) { return registry.get(id); }
    @PUT @Path("/repositories/{id}") public WorkPolicyRegistry.Policy save(@PathParam("id") UUID id, WorkPolicyRegistry.Input input) {
        try { return registry.save(id, input); }
        catch (IllegalArgumentException invalid) { throw new BadRequestException(invalid.getMessage()); }
    }
}
