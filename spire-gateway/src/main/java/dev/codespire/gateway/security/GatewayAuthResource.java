package dev.codespire.gateway.security;

import io.quarkus.oidc.OidcSession;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
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

    /**
     * Looked up rather than injected directly, because {@code quarkus.oidc.enabled} is a BUILD-time
     * switch and both dev and test run with it false. With OIDC off the {@code OidcSession} bean is
     * never produced, and a direct injection therefore failed the whole service's build with an
     * unsatisfied dependency — not at the one endpoint that needs it, but everywhere. Unresolvable
     * here means only that there is no session to end, which is exactly true when OIDC is switched
     * off.
     */
    @Inject
    Instance<OidcSession> oidcSession;

    /**
     * End this prefix's session, and only this one.
     *
     * <p>Signing out used to clear {@code /api} alone. Sessions are per prefix (ADR-022), so that left
     * this one live: after a logout the gateway still answered its attention feed and still held a
     * valid registry session, until the cookie happened to lapse of its own accord.
     *
     * <p>A <em>local</em> logout — it drops this cookie without contacting the provider — because the
     * provider session is ended exactly once, by the orchestrator's RP-initiated logout at the end of
     * the chain. A second end-session request here would be for a session already gone.
     *
     * <p>{@code POST}, and reached by {@code fetch} rather than by navigating: clearing a cookie is a
     * {@code Set-Cookie} on a response, which a fetch applies just as a navigation would, so all three
     * sessions end without a page load each. Being a POST also means a cross-site {@code GET} cannot
     * sign an operator out.
     */
    @POST
    @Path("/auth/logout")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Uni<Response> logout() {
        if (!oidcSession.isResolvable()) return Uni.createFrom().item(Response.noContent().build());
        return oidcSession.get().logout().replaceWith(Response.noContent().build());
    }

    /**
     * The middle hop of a chained sign-in — see {@code AuthResource.CHAINED_NEXT} in the orchestrator
     * for why the chain exists and why it is opt-in. This prefix's session is established by the time
     * this method runs, so all that is left is to hand the window on to the last one.
     */
    private static final String CHAINED_NEXT = "/wk/auth/login?chain=1";

    @GET
    @Path("/auth/login")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Response login(@QueryParam("chain") String chain) {
        // Back to the dashboard, not to a JSON document: this is reached by navigating the window.
        // Unchained — the default, and what the session probe reads — still answers exactly "/".
        return Response.seeOther(URI.create("1".equals(chain) ? CHAINED_NEXT : "/")).build();
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
