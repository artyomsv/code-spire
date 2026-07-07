package dev.codespire.orchestrator.provider;

import dev.codespire.contract.scm.Author;
import dev.codespire.orchestrator.security.PublicHttpsGuard;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** CRUD for registered SCM providers (spire-ui Settings -> Providers). */
@Path("/api/providers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProviderResource {

    private static final Logger LOG = Logger.getLogger(ProviderResource.class);

    private static final Set<String> AUTH_KINDS = Set.of("bearer", "basic");
    private static final Set<String> TYPES = Set.of("bitbucket-cloud", "github");

    @Inject
    ProviderRegistry registry;

    @Inject
    ProviderIdentityResolver identity;

    /**
     * SSRF guard (CWE-918) escape hatch: create/update immediately issues a
     * server-side whoami() to baseUrl, so the strict https + public-address check
     * is fail-closed by default. Only %dev and %test relax it, for the
     * http://localhost WireMock SCMs.
     */
    @ConfigProperty(name = "spire.security.allow-insecure-provider-urls")
    boolean allowInsecureProviderUrls;

    @GET
    public List<ProviderView> list() {
        return registry.list();
    }

    @GET
    @Path("/{id}")
    public ProviderView get(@PathParam("id") String id) {
        return registry.get(uuid(id)).orElseThrow(() -> new NotFoundException("No provider " + id));
    }

    @POST
    public Response create(ProviderInput in) {
        validate(in, true);
        return Response.status(Response.Status.CREATED).entity(registry.create(resolveIdentity(in))).build();
    }

    @PUT
    @Path("/{id}")
    public ProviderView update(@PathParam("id") String id, ProviderInput in) {
        validate(in, false);
        return registry.update(uuid(id), resolveIdentity(in))
                .orElseThrow(() -> new NotFoundException("No provider " + id));
    }

    /**
     * When a token is supplied, validate it against the SCM and auto-fill the bot
     * account id from the token owner if the operator left it blank. On update with
     * no new token (keeping the stored one) there is nothing new to validate, so the
     * input is passed through untouched.
     */
    ProviderInput resolveIdentity(ProviderInput in) {
        if (in.secret() == null || in.secret().isBlank()) {
            return in;
        }
        Author owner;
        try {
            owner = identity.resolve(in);
        } catch (RuntimeException e) {
            // Generic message to the client: the adapter's root cause may reflect
            // internal/upstream responses (SSRF probe echoes). Detail stays server-side.
            LOG.warnf(e, "Token validation against provider type %s failed", in.type());
            throw new BadRequestException("Could not validate the API token against the provider");
        }
        boolean hasBotId = in.botAccountId() != null && !in.botAccountId().isBlank();
        String botId = hasBotId ? in.botAccountId() : owner.providerUserId();
        return new ProviderInput(in.name(), in.type(), in.baseUrl(), in.workspace(), in.authKind(),
                in.authUsername(), in.secret(), botId, in.enabled(), in.authors());
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        if (!registry.delete(uuid(id))) {
            throw new NotFoundException("No provider " + id);
        }
        return Response.noContent().build();
    }

    private void validate(ProviderInput in, boolean creating) {
        if (in == null) {
            throw new BadRequestException("Provider body is required");
        }
        requireField(in.name(), "name");
        requireField(in.type(), "type");
        requireField(in.baseUrl(), "baseUrl");
        requireField(in.workspace(), "workspace");
        if (!TYPES.contains(in.type())) {
            throw new BadRequestException("Unsupported provider type '" + in.type()
                    + "' (expected one of: " + String.join(", ", TYPES.stream().sorted().toList()) + ")");
        }
        validateBaseUrl(in.baseUrl());
        if (in.authKind() == null || !AUTH_KINDS.contains(in.authKind())) {
            throw new BadRequestException("authKind must be 'bearer' or 'basic'");
        }
        if ("basic".equals(in.authKind()) && (in.authUsername() == null || in.authUsername().isBlank())) {
            throw new BadRequestException("authUsername is required for basic auth");
        }
        if (creating && (in.secret() == null || in.secret().isBlank())) {
            throw new BadRequestException("secret (API token) is required");
        }
    }

    /**
     * SSRF guard (CWE-918): the baseUrl is dereferenced server-side right after
     * validation (whoami), so it must be an https URL resolving to a public
     * address. Delegates to the shared {@link PublicHttpsGuard} (also used by the
     * LLM key validation), so the two server-side-fetch paths cannot drift.
     */
    void validateBaseUrl(String baseUrl) {
        PublicHttpsGuard.validate(baseUrl, allowInsecureProviderUrls);
    }

    private static void requireField(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(name + " is required");
        }
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid provider id");
        }
    }
}
