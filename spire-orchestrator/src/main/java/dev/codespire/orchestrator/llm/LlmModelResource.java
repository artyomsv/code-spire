package dev.codespire.orchestrator.llm;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
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
        try {
            if (!registry.delete(uuid(id))) {
                throw new NotFoundException("No LLM model " + id);
            }
        } catch (IllegalStateException inUse) {
            throw new ClientErrorException(
                    Response.status(Response.Status.CONFLICT).entity(inUse.getMessage()).build());
        }
        return Response.noContent().build();
    }

    private void validate(LlmModelInput in) {
        if (in == null) {
            throw badRequest("LLM model body is required");
        }
        requireField(in.type(), "type");
        requireField(in.name(), "name");
        requireField(in.label(), "label");
        requireField(in.pricingMode(), "pricingMode");
        if (!TYPES.contains(in.type())) {
            throw badRequest("Unsupported model type '" + in.type()
                    + "' (expected one of: " + String.join(", ", TYPES.stream().sorted().toList()) + ")");
        }
        // Pricing validity lives with the validator, which owns the METERED/UNMETERED rules and the
        // mandatory-dimension list. Surfaced as 400 rather than 500 because it is the caller's input.
        try {
            LlmModelPricingValidator.validate(in);
        } catch (IllegalArgumentException invalid) {
            throw badRequest(invalid.getMessage());
        }
    }

    private static void requireField(String value, String name) {
        if (value == null || value.isBlank()) {
            throw badRequest(name + " is required");
        }
    }

    // BadRequestException(String) only sets the exception's own message, not the HTTP response
    // body — the entity must be set explicitly so callers see the actionable errors.
    private static BadRequestException badRequest(String message) {
        return new BadRequestException(Response.status(Response.Status.BAD_REQUEST).entity(message).build());
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw badRequest("Invalid LLM model id");
        }
    }
}
