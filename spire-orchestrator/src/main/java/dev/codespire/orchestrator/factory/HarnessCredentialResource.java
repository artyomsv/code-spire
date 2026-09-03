package dev.codespire.orchestrator.factory;

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

    @Inject
    HarnessCredentialPool pool;

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
        HarnessCredentialPool.MemberView added =
                pool.add(in.label().trim(), in.type().trim(), in.baseUrl().trim(), in.apiKey());
        LOG.infof("harness credential %s added to the pool", added.id());
        return Response.status(Response.Status.CREATED).entity(added).build();
    }

    /**
     * Take a member out of rotation without destroying what it paid for.
     *
     * <p>Disables rather than deletes, and the schema enforces it: a {@code factory_run} row holds
     * the member by foreign key, so a hard delete would take a finished run's attribution with it —
     * which is the same call ADR-024 made when a delete button turned out to be destroying a charge
     * ledger.
     */
    @DELETE
    @Path("/{id}")
    public Response disable(@PathParam("id") String id) {
        if (!pool.remove(uuid(id))) {
            throw new NotFoundException("no such harness credential: " + id);
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
        pool.markRateLimited(uuid(id), null);
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
