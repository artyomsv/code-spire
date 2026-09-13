package dev.codespire.orchestrator.repository;

import dev.codespire.orchestrator.provider.ProviderRegistry.AccountConflict;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/** Admin-only repository coordinates, role bindings and migration repairs. */
@Path("/api/repositories")
@RolesAllowed("spire-admin")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class RepositoryResource {
    @Inject RepositoryRegistry registry;
    @Inject RepositoryMappings mappings;

    @GET @Path("/kinds")
    public List<String> kinds() { return dev.codespire.orchestrator.provider.ProviderClients.SUPPORTED_TYPES.stream().sorted().toList(); }

    @GET @Path("/pending")
    public List<RepositoryMappings.Pending> pending() { return mappings.pending(); }

    @PUT @Path("/pending/{id}")
    public Response link(@PathParam("id") UUID id, RepositoryMappings.Selection selection) {
        if (selection == null || selection.repositoryId() == null) throw new BadRequestException("Select a repository");
        try { mappings.link(id, selection.revision(), selection.repositoryId()); return Response.noContent().build(); }
        catch (AccountConflict conflict) { throw conflict(conflict); }
    }

    @GET
    public List<RepositoryView> list() { return registry.list(); }

    @GET @Path("/{id}")
    public RepositoryView get(@PathParam("id") UUID id) {
        return registry.get(id).orElseThrow(() -> new NotFoundException("Repository not registered"));
    }

    @POST
    public Response create(RepositoryInput input) {
        try { return Response.status(201).entity(registry.create(input)).build(); }
        catch (IllegalArgumentException invalid) { throw new BadRequestException(invalid.getMessage()); }
        catch (AccountConflict conflict) { throw conflict(conflict); }
    }

    @PUT @Path("/{id}")
    public RepositoryView update(@PathParam("id") UUID id, @QueryParam("revision") long revision, RepositoryInput input) {
        get(id);
        try { return registry.update(id, revision, input); }
        catch (IllegalArgumentException invalid) { throw new BadRequestException(invalid.getMessage()); }
        catch (AccountConflict conflict) { throw conflict(conflict); }
    }

    private ClientErrorException conflict(AccountConflict failure) {
        return new ClientErrorException(Response.status(409).type(MediaType.TEXT_PLAIN).entity(failure.getMessage()).build());
    }
}
