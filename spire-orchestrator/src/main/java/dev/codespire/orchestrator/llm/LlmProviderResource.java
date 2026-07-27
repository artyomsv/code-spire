package dev.codespire.orchestrator.llm;

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

/** CRUD for registered LLM providers (spire-ui Settings -> LLM). */
@Path("/api/llm-providers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LlmProviderResource {

    private static final Logger LOG = Logger.getLogger(LlmProviderResource.class);
    private static final Set<String> TYPES = Set.of("openai", "anthropic", "gemini");

    @Inject
    LlmProviderRegistry registry;

    @Inject
    LlmKeyValidator validator;

    /** Mirrors ProviderResource: fail-closed https+public-address check, relaxed only in %dev/%test. */
    @ConfigProperty(name = "spire.security.allow-insecure-provider-urls")
    boolean allowInsecureProviderUrls;

    @GET
    public List<LlmProviderView> list() {
        return registry.list();
    }

    @GET
    @Path("/{id}")
    public LlmProviderView get(@PathParam("id") String id) {
        return registry.get(uuid(id)).orElseThrow(() -> new NotFoundException("No LLM provider " + id));
    }

    @POST
    public Response create(LlmProviderInput in) {
        validate(in, true);
        validator.ping(in.type(), in.baseUrl(), in.apiKey());
        LlmProviderView created = registry.create(in);
        // validator.ping(...) just proved the key works; an apiKey is required to create
        // (validate() above), so this call always re-validated it.
        registry.recordCheck(UUID.fromString(created.id()), true, null);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @PUT
    @Path("/{id}")
    public LlmProviderView update(@PathParam("id") String id, LlmProviderInput in) {
        validate(in, false);
        // Validate the key only when a new one is supplied (blank = keep the stored key).
        boolean rotatingKey = in.apiKey() != null && !in.apiKey().isBlank();
        if (rotatingKey) {
            validator.ping(in.type(), in.baseUrl(), in.apiKey());
        }
        LlmProviderView updated = registry.update(uuid(id), in)
                .orElseThrow(() -> new NotFoundException("No LLM provider " + id));
        // Only record when a key was actually supplied and pinged: that's the only case that
        // re-validated the credential. Recording success unconditionally would silently clear a
        // real prior rejection on an update that never touched the key at all (mirrors
        // ProviderResource.update).
        if (rotatingKey) {
            registry.recordCheck(uuid(id), true, null);
        }
        return updated;
    }

    @PUT
    @Path("/{id}/default")
    public LlmProviderView makeDefault(@PathParam("id") String id) {
        return registry.setDefault(uuid(id))
                .orElseThrow(() -> new NotFoundException("No LLM provider " + id));
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        if (!registry.delete(uuid(id))) {
            throw new NotFoundException("No LLM provider " + id);
        }
        return Response.noContent().build();
    }

    /**
     * Live key check against the provider's stored credential, mirroring the SCM and context
     * providers' Check buttons. Records the outcome so the attention panel can report a key the
     * provider has started refusing. Never returns the key; only a safe category of the failure.
     */
    @POST
    @Path("/{id}/check")
    @Consumes(MediaType.WILDCARD) // no request body — don't require a JSON content type
    public CheckResult check(@PathParam("id") String id) {
        LlmProviderConfig config = registry.resolveById(uuid(id))
                .orElseThrow(() -> new NotFoundException("No LLM provider " + id));
        LlmKeyValidator.CheckOutcome outcome =
                validator.check(config.type(), config.baseUrl(), config.apiKey());
        if (outcome.ok()) {
            registry.recordCheck(config.id(), true, null);
        } else if (outcome.isRejected()) {
            // An unreachable provider (status 0) or any other non-auth failure (5xx, an unexpected
            // status) is inconclusive, not a rejected key — leave the stored last_check_ok untouched
            // so a transient outage cannot light up the row, and fixing the network can actually
            // clear it.
            registry.recordCheck(config.id(), false, outcome.detail());
        }
        if (!outcome.ok()) {
            LOG.warnf("LLM provider %s (%s) key check failed: %s", id, config.type(), outcome.detail());
        }
        return new CheckResult(outcome.ok(), outcome.detail());
    }

    /** Result of {@link #check}: a safe {@code detail} on failure, null on success. */
    public record CheckResult(boolean ok, String detail) {
    }

    private void validate(LlmProviderInput in, boolean creating) {
        if (in == null) {
            throw new BadRequestException("LLM provider body is required");
        }
        requireField(in.name(), "name");
        requireField(in.type(), "type");
        requireField(in.baseUrl(), "baseUrl");
        requireField(in.model(), "model");
        if (!TYPES.contains(in.type())) {
            throw new BadRequestException("Unsupported LLM provider type '" + in.type()
                    + "' (expected one of: " + String.join(", ", TYPES.stream().sorted().toList()) + ")");
        }
        // SSRF guard: the baseUrl is dereferenced server-side (key ping) and later by the worker.
        PublicHttpsGuard.validate(in.baseUrl(), allowInsecureProviderUrls);
        if (in.temperature() != null && (in.temperature() < 0 || in.temperature() > 2)) {
            throw new BadRequestException("temperature must be between 0 and 2");
        }
        if (in.maxTokens() != null && in.maxTokens() < 1) {
            throw new BadRequestException("maxTokens must be a positive integer");
        }
        if (creating && (in.apiKey() == null || in.apiKey().isBlank())) {
            throw new BadRequestException("apiKey is required");
        }
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
            throw new BadRequestException("Invalid LLM provider id");
        }
    }
}
