package dev.codespire.orchestrator.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * What the dashboard needs to know about its own session (D10 slice 4).
 *
 * <p>The interface cannot render correctly without this. It has to distinguish three states that
 * otherwise look identical from the browser: authentication is switched off entirely (dev — show no
 * login at all), it is on and nobody is signed in (show a login), and it is on and someone is
 * (show the dashboard, with admin actions hidden from a viewer).
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject
    SecurityIdentity identity;

    @ConfigProperty(name = "spire.security.auth-enabled", defaultValue = "true")
    boolean authEnabled;

    /**
     * @param authEnabled whether this deployment authenticates at all
     * @param authenticated whether THIS caller is signed in
     * @param user the signed-in operator, or empty
     * @param roles the operator's spire roles, or empty
     */
    public record Me(boolean authEnabled, boolean authenticated, String user, List<String> roles) {
    }

    /**
     * Deliberately public — the one exception to this service's deny-by-default policy.
     *
     * <p>A browser must be able to ask whether it needs to log in <em>before</em> it has logged in;
     * gating this behind authentication would make the answer unobtainable exactly when it is needed.
     * It is safe to expose because it describes the caller, not the system: an anonymous caller
     * learns only that authentication is switched on, which the login redirect would tell them anyway.
     */
    @GET
    @Path("/me")
    public Me me() {
        boolean signedIn = !identity.isAnonymous();
        return new Me(
                authEnabled,
                signedIn,
                signedIn ? identity.getPrincipal().getName() : "",
                signedIn ? spireRoles(identity.getRoles()) : List.of());
    }

    /** Only this application's own roles; an operator's other realm roles are not our business. */
    private static List<String> spireRoles(Set<String> roles) {
        return roles.stream().filter(r -> r.startsWith("spire-")).sorted().toList();
    }

    /**
     * The dashboard's login entry point.
     *
     * <p>A single-page app cannot start an authorization-code flow from {@code fetch} — the redirect
     * is cross-origin and the browser reports it as an opaque failure. So the interface navigates the
     * whole window here instead. This path is protected, so an anonymous request is redirected to the
     * identity provider; once the flow completes the framework restores this path, and the operator
     * lands back on the dashboard rather than on a JSON document.
     */
    /**
     * Where a chained sign-in goes next. A session is per prefix (ADR-022), so a cold sign-in has to
     * establish three of them — and each one needs a real navigation, because a cookie cannot be minted
     * by {@code fetch}. Returning the operator to {@code /} after each meant the dashboard booted,
     * fetched, discovered the next missing session and navigated again: three renders thrown away, seen
     * as the app blanking and restarting.
     *
     * <p>Handing off to the next prefix instead keeps it a single redirect sequence. The browser follows
     * redirects without rendering the documents in between, so the dashboard is painted exactly once.
     *
     * <p>Only when asked, via {@code chain=1}. Without the parameter this endpoint still answers
     * {@code /} exactly as before, which is what keeps the session probes honest: they read this path to
     * ask "does my prefix have a session", and an answer that depended on a LATER prefix would make a
     * healthy service look unauthenticated. It is also why no client-supplied URL is ever redirected to
     * — the hop is this constant, so the parameter cannot become an open redirect.
     */
    private static final String CHAINED_NEXT = "/gw/auth/login?chain=1";

    @GET
    @Path("/auth/login")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Response login(@QueryParam("chain") String chain) {
        return Response.seeOther(URI.create("1".equals(chain) ? CHAINED_NEXT : "/")).build();
    }

    /**
     * A code-flow callback the framework did not claim — send the operator to the dashboard.
     *
     * <p>The redirect path is normally intercepted by the OIDC mechanism before routing ever sees it,
     * but only while there is a state cookie to match the callback's {@code state} against. That cookie
     * lives five minutes by default, so a login page left open past it — or submitted twice — produces
     * a callback the mechanism declines, which then falls through to ordinary routing and finds no
     * resource here. The result was a **404 immediately after entering valid credentials**, which reads
     * as the sign-in being broken.
     *
     * <p>Nothing is authenticated by this method: reaching it at all means the request already
     * satisfied the policy on this prefix, so the session exists and the sensible answer is the page
     * the operator was trying to get to. A genuine callback still never arrives here.
     *
     * <p>In dev the fall-through was worse than a bare 404: Quarkus answers an unmatched path with its
     * development "resources overview" page, which lists every endpoint in the service. It is
     * dev-mode-only ({@code io.quarkus.vertx.http.runtime.devmode}) and was never publicly reachable —
     * the policy answers an anonymous request with a redirect — but it did disclose the API surface to
     * any signed-in operator, including a viewer.
     */
    @GET
    @Path("/auth/callback")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Response staleCallback() {
        return Response.seeOther(URI.create("/")).build();
    }
}
