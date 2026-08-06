package dev.codespire.worker.security;

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
 * This service's own login entry point — see {@code GatewayAuthResource} for why each prefix needs
 * one of these.
 *
 * <p>The dashboard reads a review's assembled context from {@code /wk}, so a review detail page that
 * could not obtain a {@code /wk} session rendered its Context card as a failed request while every
 * other card on the same page worked.
 *
 * <p>Both roles: reading a review's context is part of reading the review.
 */
@Path("/wk")
public class WorkerAuthResource {

    /** Looked up, not injected — see {@code GatewayAuthResource.oidcSession} for why a direct
     *  injection breaks every build that has OIDC switched off. */
    @Inject
    Instance<OidcSession> oidcSession;

    /**
     * End this prefix's session, and only this one — see {@code GatewayAuthResource.logout} for why
     * this is a local logout reached by POST, and what signing out left behind before it existed.
     */
    @POST
    @Path("/auth/logout")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Uni<Response> logout() {
        if (!oidcSession.isResolvable()) return Uni.createFrom().item(Response.noContent().build());
        return oidcSession.get().logout().replaceWith(Response.noContent().build());
    }

    @GET
    @Path("/auth/login")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Response login() {
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * A code-flow callback the framework did not claim — see {@code AuthResource.staleCallback} in the
     * orchestrator for the full reasoning.
     */
    @GET
    @Path("/auth/callback")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Response staleCallback() {
        return Response.seeOther(URI.create("/")).build();
    }
}
