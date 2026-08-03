package dev.codespire.orchestrator.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
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
    @GET
    @Path("/auth/login")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Response login() {
        return Response.seeOther(URI.create("/")).build();
    }
}
