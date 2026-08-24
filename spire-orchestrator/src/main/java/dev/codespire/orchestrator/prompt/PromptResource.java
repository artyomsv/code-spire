package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptValidation;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
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

/**
 * CRUD + validation + preview for operator prompt overrides (spire-ui Settings -> Prompts).
 *
 * <p>Admin-only in full, reads included: a prompt override is the instruction set every review is
 * conducted under, and the preview renders it against real input.
 */
@Path("/api/prompts")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PromptResource {

    @Inject
    PromptRegistry registry;

    @Inject
    PromptSampleRenderer sampleRenderer;

    @Inject
    ReviewProjection projection;

    /**
     * Preview response: the assembled system + user, and any validation errors.
     *
     * @param sampleReviewId the review the sample was rendered against, or {@code null} when no
     *                       {@code reviewId} was given or the sample could not be assembled
     * @param unavailableReason set only when a {@code reviewId} was given but its sample could not
     *                          be assembled — why the response fell back to the annotated preview
     */
    public record PreviewResult(String system, String user, List<String> errors,
                                String sampleReviewId, String unavailableReason) {
    }

    @GET
    public List<PromptView> list(@QueryParam("scope") @DefaultValue(PromptScope.GLOBAL) String scope) {
        return registry.list(parseScope(scope));
    }

    /**
     * Repositories this deployment has reviewed -- the scopes an override can be written at.
     * Read from the orchestrator's own review rows, NOT the gateway's webhook_repo: that table
     * belongs to another service behind its own URL prefix and session (ADR-022), and a settings
     * dropdown is not a reason to couple them. A repo nobody has reviewed is also one there is
     * nothing to preview a template against.
     */
    @GET
    @Path("/scopes")
    public List<String> scopes() {
        return projection.knownRepoScopes();
    }

    @GET
    @Path("/{kind}")
    public PromptView get(@PathParam("kind") String kind,
                          @QueryParam("scope") @DefaultValue(PromptScope.GLOBAL) String scope) {
        return registry.effective(parse(kind), parseScope(scope));
    }

    @PUT
    @RolesAllowed("spire-admin")
    @Path("/{kind}")
    public PromptView save(@PathParam("kind") String kind,
                           @QueryParam("scope") @DefaultValue(PromptScope.GLOBAL) String scope, PromptInput in) {
        PromptKind promptKind = parse(kind);
        String resolvedScope = parseScope(scope);
        requireBody(in);
        List<String> errors = PromptValidation.validate(promptKind, in.system(), in.body());
        if (!errors.isEmpty()) {
            throw badRequest(errors);
        }
        registry.save(promptKind, resolvedScope, in.system(), in.body());
        return registry.effective(promptKind, resolvedScope);
    }

    @DELETE
    @RolesAllowed("spire-admin")
    @Path("/{kind}")
    public Response reset(@PathParam("kind") String kind,
                          @QueryParam("scope") @DefaultValue(PromptScope.GLOBAL) String scope) {
        registry.reset(parse(kind), parseScope(scope));
        return Response.noContent().build();
    }

    /**
     * Keep the customization, stop reporting drift: re-stamp the ancestor to what ships now.
     * Deliberately not a reset variant -- reset discards the customization, this preserves it.
     *
     * <p>Scoped like every other mutation here: drift is a property of one customization, and after
     * the (scope, kind) re-key a customization is per scope, so an unscoped accept would clear the
     * drift flag on a row the operator was not looking at.
     */
    @POST
    @RolesAllowed("spire-admin")
    @Path("/{kind}/accept-default")
    @Consumes(MediaType.WILDCARD) // no request body
    public Response acceptDefault(@PathParam("kind") String kind,
                                  @QueryParam("scope") @DefaultValue(PromptScope.GLOBAL) String scope) {
        registry.acceptCurrentDefault(parse(kind), parseScope(scope));
        return Response.noContent().build();
    }

    @POST
    // Admin-only via the class annotation, and that matters more now: with a reviewId this renders a
    // real pull request's source code into the response. It writes nothing and calls no LLM — the
    // POST is only because the body carries the draft.
    @Path("/{kind}/preview")
    public PreviewResult preview(@PathParam("kind") String kind,
                                 @QueryParam("scope") @DefaultValue(PromptScope.GLOBAL) String scope,
                                 PromptInput in) {
        PromptKind promptKind = parse(kind);
        parseScope(scope); // validated for the same reason every other endpoint here validates it —
                            // a malformed value must be a 400, not a stored key nobody can address —
                            // even though the preview renders the draft it is given, not a stored row.
        requireBody(in);
        List<String> errors = PromptValidation.validate(promptKind, in.system(), in.body());
        if (in.reviewId() == null || in.reviewId().isBlank()) {
            PromptValidation.PromptPreview p =
                    PromptValidation.preview(promptKind, in.system(), in.body());
            return new PreviewResult(p.system(), p.user(), errors, null, null);
        }
        try {
            PromptValidation.PromptPreview p =
                    sampleRenderer.render(promptKind, in.system(), in.body(), in.reviewId());
            return new PreviewResult(p.system(), p.user(), errors, in.reviewId(), null);
        } catch (PromptSampleRenderer.PromptSampleUnavailable unavailable) {
            // Fall back to the annotated preview WITH the reason. An empty panel reads as a broken
            // feature; the reason tells the operator to pick a different review.
            PromptValidation.PromptPreview p =
                    PromptValidation.preview(promptKind, in.system(), in.body());
            return new PreviewResult(p.system(), p.user(), errors, null, unavailable.getMessage());
        }
    }

    private static void requireBody(PromptInput in) {
        if (in == null || in.body() == null) {
            throw badRequest(List.of("system and body are required"));
        }
    }

    // PromptScope.parse throws IllegalArgumentException -- unhandled that surfaces as a 500. The
    // scope arrives from a query param and becomes a primary-key component, so a malformed value
    // (e.g. "../../etc") must be an actionable 400 instead, the same as every other rejected input
    // on this resource.
    private static String parseScope(String scope) {
        try {
            return PromptScope.parse(scope);
        } catch (IllegalArgumentException e) {
            throw badRequest(List.of(e.getMessage()));
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
