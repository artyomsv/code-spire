package dev.codespire.orchestrator.provider;

import dev.codespire.contract.port.ActorDirectory;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;

@Path("/api/repositories/{repository}/fix-actors")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RepositoryActorResource {
    @Inject ActorResolutionResource actors;
    @POST @Path("/resolve")
    public ActorDirectory.Result resolve(@PathParam("repository") UUID repository, ActorResolutionResource.Input input) {
        return actors.resolveRepository(repository, input);
    }
    @GET
    public List<ActorDisplay> list(@PathParam("repository") UUID repository, @QueryParam("refresh") @DefaultValue("false") boolean refresh) {
        return actors.repositoryActors(repository, refresh);
    }
    @POST
    public List<ActorDisplay> save(@PathParam("repository") UUID repository, ActorResolutionResource.Input input) {
        return actors.saveRepository(repository, input);
    }
    @DELETE @Path("/{actor}")
    public List<ActorDisplay> delete(@PathParam("repository") UUID repository, @PathParam("actor") String actor, @QueryParam("revision") long revision) {
        return actors.deleteRepository(repository, actor, revision);
    }
}
