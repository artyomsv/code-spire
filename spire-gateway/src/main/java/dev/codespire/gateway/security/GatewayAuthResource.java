package dev.codespire.gateway.security;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * This service's own login entry point.
 *
 * <p>It exists because a session here is <em>not</em> the orchestrator's. Each service is its own OIDC
 * client with its own cookie scoped to its own URL prefix (ADR-022) — that is what stops one service
 * receiving another's credential — and the consequence is that signing in to the dashboard mints the
 * cookie for {@code /api} and nothing else. Without this endpoint there was no way to obtain a
 * {@code /gw} session at all: every call the dashboard made here answered 302-to-the-provider, which a
 * {@code fetch} cannot follow cross-origin, so an entire page reported "failed to fetch" and the
 * attention socket reported the gateway as down while it was serving webhooks perfectly well.
 *
 * <p>Reaching it is not a second sign-in. The provider session already exists, so the authorization
 * code flow completes without prompting; the operator sees one redirect and comes back. The path is
 * protected precisely so that an anonymous request starts that flow.
 *
 * <p>Both roles: the registry behind {@code /gw} is admin-only, but the attention feed on the same
 * prefix is readable by a viewer, and a viewer needs a session to read it.
 */
@Path("/gw")
public class GatewayAuthResource {

    @GET
    @Path("/auth/login")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Response login() {
        // Back to the dashboard, not to a JSON document: this is reached by navigating the window.
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * A code-flow callback the framework did not claim — see {@code AuthResource.staleCallback} in the
     * orchestrator for the full reasoning. Every prefix that terminates its own OIDC flow needs one,
     * because every one of them can be reached with an expired state cookie.
     */
    @GET
    @Path("/auth/callback")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Response staleCallback() {
        return Response.seeOther(URI.create("/")).build();
    }
}
