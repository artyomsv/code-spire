package dev.codespire.orchestrator.analytics;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Administering the operator-to-SCM mapping (P4 / FR-11).
 *
 * <p><b>Admin-only including its reads.</b> ADR-022's third rule makes every registry so, on the
 * grounds that a listing is an inventory — and this one is worse than an inventory of endpoints: it
 * is a map from named people to the activity measured about them.
 */
@Path("/api/operator-identities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("spire-admin")
public class OperatorIdentityResource {

    /** An operator hands their subject over from their own profile view; an admin types the rest. */
    public record LinkRequest(String oidcSubject, String providerType, String authorId) {
    }

    @Inject
    OperatorIdentities identities;

    @GET
    public List<OperatorIdentities.Link> list() {
        return identities.all();
    }

    @POST
    public Response link(LinkRequest request) {
        String rejection = rejectionFor(request);
        if (rejection != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(rejection).build();
        }
        identities.link(new OperatorIdentities.Link(request.oidcSubject().trim(),
                request.providerType().trim(), request.authorId().trim()));
        return Response.noContent().build();
    }

    @DELETE
    @Path("/{oidcSubject}/{providerType}")
    public Response unlink(@PathParam("oidcSubject") String oidcSubject,
                           @PathParam("providerType") String providerType) {
        return identities.unlink(oidcSubject, providerType)
                ? Response.noContent().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    /**
     * Every field is required, and the message says which one is missing.
     *
     * <p>A blank {@code providerType} would be the damaging one: the mapping would then match no
     * review, the operator would see the unlinked state forever, and nothing would explain why.
     */
    private static String rejectionFor(LinkRequest request) {
        if (request == null) {
            return "A mapping is required.";
        }
        if (isBlank(request.oidcSubject())) {
            return "oidcSubject is required — the operator reads it from their own profile view.";
        }
        if (isBlank(request.providerType())) {
            return "providerType is required — an author id alone is not a person, since the same id "
                    + "on two platforms belongs to two different people.";
        }
        if (isBlank(request.authorId())) {
            return "authorId is required — the SCM's stable user id, shown on the review detail page.";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
