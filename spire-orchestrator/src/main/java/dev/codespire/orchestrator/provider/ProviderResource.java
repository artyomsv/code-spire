package dev.codespire.orchestrator.provider;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** CRUD for registered SCM providers (spire-ui Settings -> Providers). */
@Path("/api/providers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProviderResource {

    private static final Set<String> AUTH_KINDS = Set.of("bearer", "basic");
    private static final Set<String> TYPES = Set.of("bitbucket-cloud", "github");

    @Inject
    ProviderRegistry registry;

    @GET
    public List<ProviderView> list() {
        return registry.list();
    }

    @GET
    @Path("/{id}")
    public ProviderView get(@PathParam("id") String id) {
        return registry.get(uuid(id)).orElseThrow(() -> new NotFoundException("No provider " + id));
    }

    @POST
    public Response create(ProviderInput in) {
        validate(in, true);
        return Response.status(Response.Status.CREATED).entity(registry.create(in)).build();
    }

    @PUT
    @Path("/{id}")
    public ProviderView update(@PathParam("id") String id, ProviderInput in) {
        validate(in, false);
        return registry.update(uuid(id), in).orElseThrow(() -> new NotFoundException("No provider " + id));
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        if (!registry.delete(uuid(id))) {
            throw new NotFoundException("No provider " + id);
        }
        return Response.noContent().build();
    }

    private static void validate(ProviderInput in, boolean creating) {
        if (in == null) {
            throw new BadRequestException("Provider body is required");
        }
        requireField(in.name(), "name");
        requireField(in.type(), "type");
        requireField(in.baseUrl(), "baseUrl");
        requireField(in.workspace(), "workspace");
        if (!TYPES.contains(in.type())) {
            throw new BadRequestException("Unsupported provider type '" + in.type() + "' (expected bitbucket-cloud)");
        }
        if (in.authKind() == null || !AUTH_KINDS.contains(in.authKind())) {
            throw new BadRequestException("authKind must be 'bearer' or 'basic'");
        }
        if ("basic".equals(in.authKind()) && (in.authUsername() == null || in.authUsername().isBlank())) {
            throw new BadRequestException("authUsername is required for basic auth");
        }
        if (creating && (in.secret() == null || in.secret().isBlank())) {
            throw new BadRequestException("secret (API token) is required");
        }
    }

    private static void requireField(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(name + " is required");
        }
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid provider id");
        }
    }
}
