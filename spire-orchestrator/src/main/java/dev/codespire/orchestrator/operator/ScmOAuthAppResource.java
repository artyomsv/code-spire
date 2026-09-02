package dev.codespire.orchestrator.operator;

import dev.codespire.orchestrator.security.PublicHttpsGuard;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Setting up the OAuth applications operators sign into (FR-11).
 *
 * <p>Admin-only including its reads, like every registry: a client id names an application this
 * deployment is trusted by, and the listing is an inventory of which platforms it can reach — the
 * third of ADR-022's three rules.
 */
@Path("/api/scm-oauth-apps")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("spire-admin")
public class ScmOAuthAppResource {

    /**
     * One platform's setup, plus the two things an admin cannot work out for themselves.
     *
     * @param redirectUri the callback to register on the OAuth app — computed from the request, so
     *     it is right behind a proxy and an admin never has to guess this deployment's public
     *     address. A wrong one fails at the SCM with a message that names nothing in this product.
     * @param connectable whether this build actually has an adapter for the platform; without one a
     *     saved app would produce a button that can only ever fail
     */
    public record View(String providerType, String webBaseUrl, String apiBaseUrl, String clientId,
                       boolean hasSecret, boolean connectable, String redirectUri) {
    }

    @Inject
    ScmOAuthApps apps;

    /**
     * Same escape hatch as every other provider URL, and it must stay the same one: a deployment
     * that can point a bot at a local WireMock has to be able to point a sign-in there too, or the
     * flow is the one thing in this product that cannot be exercised outside production.
     */
    @ConfigProperty(name = "spire.security.allow-insecure-provider-urls")
    boolean allowInsecureProviderUrls;

    @GET
    public List<View> list(@Context UriInfo uriInfo) {
        List<ScmOAuthApps.View> saved = apps.list();
        return OperatorConnects.SUPPORTED_TYPES.stream().sorted()
                .map(type -> saved.stream().filter(v -> v.providerType().equals(type)).findFirst()
                        .map(v -> new View(type, v.webBaseUrl(), v.apiBaseUrl(), v.clientId(),
                                v.hasSecret(), true, OperatorConnectResource.callbackUri(uriInfo, type)))
                        .orElseGet(() -> new View(type, null, null, "", false, true,
                                OperatorConnectResource.callbackUri(uriInfo, type))))
                .toList();
    }

    @POST
    public Response save(ScmOAuthApps.Input in) {
        String rejection = rejectionFor(in);
        if (rejection != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(rejection).build();
        }
        return Response.ok(apps.save(in)).build();
    }

    @DELETE
    @Path("/{providerType}")
    public Response delete(@PathParam("providerType") String providerType) {
        return apps.delete(providerType)
                ? Response.noContent().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    /**
     * Every field that has to be right, checked here rather than at the SCM.
     *
     * <p>The secret is required on a FIRST save and optional afterwards, because a blank one keeps
     * what is stored — an admin correcting a base URL must not silently erase the credential, which
     * is the trap the provider settings form already carries a warning about.
     */
    private String rejectionFor(ScmOAuthApps.Input in) {
        if (in == null || in.providerType() == null || in.providerType().isBlank()) {
            return "providerType is required.";
        }
        if (!OperatorConnects.SUPPORTED_TYPES.contains(in.providerType())) {
            return "This build cannot sign an operator in to " + in.providerType() + ".";
        }
        if (in.clientId() == null || in.clientId().isBlank()) {
            return "clientId is required — it is on the OAuth application you registered.";
        }
        boolean known = apps.list().stream().anyMatch(v -> v.providerType().equals(in.providerType()));
        if (!known && (in.clientSecret() == null || in.clientSecret().isBlank())) {
            return "clientSecret is required the first time. Leave it blank later to keep the stored one.";
        }
        String badUrl = firstBadUrl(in.webBaseUrl(), in.apiBaseUrl());
        if (badUrl != null) {
            return badUrl;
        }
        return null;
    }

    /** Both base URLs go through the same guard the bot credentials' base URLs do (CWE-918). */
    private String firstBadUrl(String... urls) {
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            try {
                PublicHttpsGuard.validate(url, allowInsecureProviderUrls);
            } catch (RuntimeException rejected) {
                return rejected.getMessage();
            }
        }
        return null;
    }
}
