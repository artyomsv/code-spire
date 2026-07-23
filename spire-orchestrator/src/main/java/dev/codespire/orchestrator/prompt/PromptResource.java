package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptValidation;
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

/** CRUD + validation + preview for operator prompt overrides (spire-ui Settings -> Prompts). */
@Path("/api/prompts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PromptResource {

    @Inject
    PromptRegistry registry;

    /** Preview response: the assembled system + annotated user, and any validation errors. */
    public record PreviewResult(String system, String user, List<String> errors) {
    }

    @GET
    public List<PromptView> list() {
        return registry.list();
    }

    @GET
    @Path("/{kind}")
    public PromptView get(@PathParam("kind") String kind) {
        return registry.effective(parse(kind));
    }

    @PUT
    @Path("/{kind}")
    public PromptView save(@PathParam("kind") String kind, PromptInput in) {
        PromptKind promptKind = parse(kind);
        requireBody(in);
        List<String> errors = PromptValidation.validate(promptKind, in.system(), in.body());
        if (!errors.isEmpty()) {
            throw badRequest(errors);
        }
        registry.save(promptKind, in.system(), in.body());
        return registry.effective(promptKind);
    }

    @DELETE
    @Path("/{kind}")
    public Response reset(@PathParam("kind") String kind) {
        registry.reset(parse(kind));
        return Response.noContent().build();
    }

    @POST
    @Path("/{kind}/preview")
    public PreviewResult preview(@PathParam("kind") String kind, PromptInput in) {
        PromptKind promptKind = parse(kind);
        requireBody(in);
        List<String> errors = PromptValidation.validate(promptKind, in.system(), in.body());
        PromptValidation.PromptPreview p = PromptValidation.preview(promptKind, in.system(), in.body());
        return new PreviewResult(p.system(), p.user(), errors);
    }

    private static void requireBody(PromptInput in) {
        if (in == null || in.body() == null) {
            throw badRequest(List.of("system and body are required"));
        }
    }

    private static PromptKind parse(String kind) {
        try {
            return PromptKind.fromSlug(kind);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("No prompt kind '" + kind + "'");
        }
    }

    // BadRequestException(String) only sets the exception's own message, not the HTTP
    // response body — the entity must be set explicitly so callers see the actionable errors.
    private static BadRequestException badRequest(List<String> errors) {
        return new BadRequestException(Response.status(Response.Status.BAD_REQUEST).entity(errors).build());
    }
}
