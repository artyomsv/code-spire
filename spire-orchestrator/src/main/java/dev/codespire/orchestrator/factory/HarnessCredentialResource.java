package dev.codespire.orchestrator.factory;

import dev.codespire.orchestrator.security.PublicHttpsGuard;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * The harness credential pool's operator surface (FR-F12).
 *
 * <p>Admin only, and by ADR-022's third rule rather than its first: this is CONFIGURATION, so even
 * the listing is admin — a pool listing is an inventory of every model endpoint this deployment
 * reaches, which is the same argument that made every other registry's reads admin-only.
 *
 * <p><b>No response ever carries a key.</b> {@link HarnessCredentialPool.MemberView} has no field for
 * one, so this is a property of the type rather than of remembering to strip it — the shape the SCM
 * and LLM registries already settled on after learning that {@code hasSecret} is the most a read may
 * say.
 */
@Path("/api/harness-credentials")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
public class HarnessCredentialResource {

    private static final Logger LOG = Logger.getLogger(HarnessCredentialResource.class);

    /** The vendor types this deployment can speak to, as the LLM registry lists them. */
    private static final java.util.Set<String> TYPES = java.util.Set.of("openai", "anthropic", "gemini");

    @Inject
    HarnessCredentialPool pool;

    /**
     * Relaxes the https/public-host guard in dev and test only, exactly as every sibling registry
     * reads it. The same property name, so one setting governs every operator-entered base URL.
     */
    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "spire.security.allow-insecure-provider-urls", defaultValue = "false")
    boolean allowInsecureProviderUrls;

    /** What an operator sends to add a member. The key is write-only and never read back. */
    public record NewCredential(String label, String type, String baseUrl, String apiKey) {
    }

    @GET
    public List<HarnessCredentialPool.MemberView> list() {
        return pool.list();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response add(NewCredential in) {
        if (in == null || blank(in.label()) || blank(in.type()) || blank(in.baseUrl()) || blank(in.apiKey())) {
            throw badRequest("label, type, baseUrl and apiKey are all required. The key is stored "
                    + "encrypted and is never returned by this API.");
        }
        if (!TYPES.contains(in.type().trim())) {
            throw badRequest("type must be one of " + TYPES + ", was: " + in.type().trim());
        }
        // The SSRF/https guard every other registry applies, and it matters MORE here: this is the
        // endpoint an agent container would be pointed at, so an http:// entry ships the key in
        // cleartext off the sandbox network and a private address aims the agent at internal
        // infrastructure. This was the one registry without it.
        PublicHttpsGuard.validate(in.baseUrl().trim(), allowInsecureProviderUrls);
        HarnessCredentialPool.MemberView added;
        try {
            added = pool.add(in.label().trim(), in.type().trim(), in.baseUrl().trim(), in.apiKey());
        } catch (HarnessCredentialPool.DuplicateLabelException e) {
            // 409 rather than the bare 500 a unique violation used to produce. The label is
            // load-bearing: the migration says outright that "which key is dead" is unanswerable
            // when two are called the same.
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity(e.getMessage()).build(), e);
        }
        LOG.infof("harness credential %s added to the pool", added.id());
        return Response.status(Response.Status.CREATED).entity(added).build();
    }

    /**
     * Take a member out of rotation without destroying what it paid for.
     *
     * <p>Disables rather than deletes, and the schema is what makes that the only option: a
     * {@code factory_run} row references the member and the foreign key carries no
     * {@code ON DELETE}, so a hard delete is REFUSED rather than cascaded.
     *
     * <p>This javadoc previously said the opposite — that a delete would take the attribution with
     * it — while three other places in the same change stated it correctly. The operator-facing
     * one was the wrong one.
     *
     * <p>Reversible: see {@link #enable}. Disabling the last member otherwise left an operator with
     * a pool refusing every run and an add that 409s on the label they had just disabled.
     */
    @DELETE
    @Path("/{id}")
    public Response disable(@PathParam("id") String id) {
        if (!pool.remove(uuid(id))) {
            throw new NotFoundException("no such harness credential: " + id);
        }
        return Response.noContent().build();
    }

    /** Return a disabled member to the pool. Disabling is not deletion, so it is not one-way. */
    @POST
    @Path("/{id}/enable")
    @Consumes(MediaType.WILDCARD)
    public Response enable(@PathParam("id") String id) {
        if (!pool.enable(uuid(id))) {
            throw new NotFoundException("no disabled harness credential with id: " + id);
        }
        return Response.noContent().build();
    }

    /**
     * The operator says a refused key works again — a rotated secret, or restored credit.
     *
     * <p>This is the ONLY way a rejection clears. Nothing expires it, because a key the provider
     * refused will be refused again, and a pool that retried it would spend one paid run per attempt
     * to rediscover that.
     */
    @POST
    @Path("/{id}/clear-rejection")
    @Consumes(MediaType.WILDCARD)
    public Response clearRejection(@PathParam("id") String id) {
        if (!pool.clearRejection(uuid(id))) {
            throw new NotFoundException("no such harness credential awaiting a cleared rejection: " + id);
        }
        LOG.warnf("harness credential %s was returned to the pool by an operator", id);
        return Response.noContent().build();
    }

    /**
     * Rest a member by hand.
     *
     * <p>Here because <b>no harness reports a rate limit distinctly</b>, so nothing marks one
     * automatically. The run path deliberately does not infer it: {@code MODEL_UNAVAILABLE} covers a
     * provider outage as well as a rate limit and cannot tell them apart, and treating an outage as
     * exhaustion would rest every member of a small pool at once — turning a transient fault into a
     * refusal quoting a recovery time nobody can rely on.
     *
     * <p>So the mechanism exists, is tested, and is driven by an operator until a harness can say
     * "rate limited" in its own words. That is the same shape as the steer capability no shipped
     * harness declares: stated here rather than discovered later from a code path nothing reaches.
     */
    @POST
    @Path("/{id}/rest")
    @Consumes(MediaType.WILDCARD)
    public Response rest(@PathParam("id") String id) {
        // 404 like its two siblings. It used to answer 204 whatever happened, so an operator who
        // mistyped an id -- or rested an already-refused member, whose CHECK makes the write a
        // no-op -- got a success AND a log line asserting a rest that was never written.
        if (!pool.markRateLimited(uuid(id), null)) {
            throw new NotFoundException("no harness credential to rest: " + id
                    + " (an already-refused member cannot rest -- clear its rejection first)");
        }
        return Response.noContent().build();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw badRequest("not a credential id: " + id);
        }
    }

    private static ClientErrorException badRequest(String message) {
        return new ClientErrorException(
                Response.status(Response.Status.BAD_REQUEST).entity(message).build());
    }

}
