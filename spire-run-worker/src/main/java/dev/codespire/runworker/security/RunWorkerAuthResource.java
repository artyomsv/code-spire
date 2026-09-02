package dev.codespire.runworker.security;

import io.quarkus.oidc.OidcSession;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * The session endpoints every deployable exposes under its own prefix (ADR-022): a browser session
 * is per prefix and must be established per service, and the dashboard probes each sibling's
 * {@code /auth/login} once signed in. Without this, the first operator screen that lands under
 * {@code /rw} would be unreachable from the dashboard — the exact incident CLAUDE.md records for the
 * gateway and worker screens.
 */
@Path("/rw")
public class RunWorkerAuthResource {

    @Inject
    Instance<OidcSession> oidcSession;

    @POST
    @Path("/auth/logout")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Uni<Response> logout() {
        if (!oidcSession.isResolvable()) {
            return Uni.createFrom().item(Response.noContent().build());
        }
        return oidcSession.get().logout().replaceWith(Response.noContent().build());
    }

    @GET
    @Path("/auth/login")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Response login() {
        return Response.seeOther(URI.create("/")).build();
    }

    @GET
    @Path("/auth/callback")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Response staleCallback() {
        return Response.seeOther(URI.create("/")).build();
    }
}
