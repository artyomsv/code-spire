package dev.codespire.orchestrator.llm;

import jakarta.annotation.security.RolesAllowed;
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

/**
 * CRUD for the LLM model catalog (spire-ui Settings -> LLM).
 *
 * <p>Admin-only in full, reads included: it carries the operator-entered prices every review is
 * costed against (ADR-018), so it is configuration in the same sense the provider list is.
 */
@Path("/api/llm-models")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LlmModelResource {

    // Must match the provider types in LlmProviderResource.
    private static final Set<String> TYPES = Set.of("openai", "anthropic", "gemini");

    @Inject
    LlmModelRegistry registry;

    @GET
    public List<LlmModelView> list() {
        return registry.list();
    }

    @POST
    @RolesAllowed("spire-admin")
    public Response create(LlmModelInput in) {
        validate(in);
        return Response.status(Response.Status.CREATED).entity(registry.create(in)).build();
    }

    @PUT
    @RolesAllowed("spire-admin")
    @Path("/{id}")
    public LlmModelView update(@PathParam("id") String id, LlmModelInput in) {
        validate(in);
        return registry.update(uuid(id), in).orElseThrow(() -> new NotFoundException("No LLM model " + id));
    }

    @DELETE
    @RolesAllowed("spire-admin")
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        if (!registry.delete(uuid(id))) {
            throw new NotFoundException("No LLM model " + id);
        }
        return Response.noContent().build();
    }

    private void validate(LlmModelInput in) {
        if (in == null) {
            throw new BadRequestException("LLM model body is required");
        }
        requireField(in.type(), "type");
        requireField(in.name(), "name");
        requireField(in.label(), "label");
        if (!TYPES.contains(in.type())) {
            throw new BadRequestException("Unsupported model type '" + in.type()
                    + "' (expected one of: " + String.join(", ", TYPES.stream().sorted().toList()) + ")");
        }
        requireNonNegative(in.inputPriceMillicentsPerMillion(), "inputPriceMillicentsPerMillion");
        requireNonNegative(in.outputPriceMillicentsPerMillion(), "outputPriceMillicentsPerMillion");
    }

    private static void requireField(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(name + " is required");
        }
    }

    private static void requireNonNegative(Long value, String name) {
        if (value == null || value < 0) {
            throw new BadRequestException(name + " must be zero or positive");
        }
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid LLM model id");
        }
    }
}
