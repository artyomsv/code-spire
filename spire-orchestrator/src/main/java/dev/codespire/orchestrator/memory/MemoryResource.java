package dev.codespire.orchestrator.memory;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Deciding what the reviewer should stop saying (P4 / FR-10).
 *
 * <p><b>Admin-only including its reads</b>, because approving a preference changes what every future
 * review posts — the "is it configuration" limb of ADR-022's three rules.
 */
@Path("/api/memory/preferences")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("spire-admin")
public class MemoryResource {

    /** What the thresholds were when this ran, so the card can show the bar as well as the score. */
    public record Thresholds(int minEvidence, int minDismissedPercent) {
    }

    public record MemoryView(List<LearnedPreferences.Preference> preferences, Thresholds thresholds) {
    }

    @Inject
    LearnedPreferences preferences;

    @Inject
    PreferenceProposals proposals;

    @Inject
    SecurityIdentity identity;

    @GET
    public MemoryView list() {
        return new MemoryView(preferences.all(),
                new Thresholds(proposals.minEvidence(), proposals.minDismissedPercent()));
    }

    @POST
    @Path("/{id}/approve")
    public Response approve(@PathParam("id") long id) {
        return decided(preferences.decide(id, LearnedPreferences.APPROVED, decider()));
    }

    @POST
    @Path("/{id}/reject")
    public Response reject(@PathParam("id") long id) {
        return decided(preferences.decide(id, LearnedPreferences.REJECTED, decider()));
    }

    /**
     * Stops an approved preference from hiding anything, without deleting the evidence.
     *
     * <p>Returning it to {@code PROPOSED} rather than {@code REJECTED} on purpose: revoking says
     * "stop doing this", which is not the same as "never ask me again". The suppressed findings come
     * back on the next review with no rebuild.
     */
    @POST
    @Path("/{id}/revoke")
    public Response revoke(@PathParam("id") long id) {
        return decided(preferences.revoke(id));
    }

    /**
     * Rescans now instead of waiting for the nightly pass.
     *
     * <p>Reads only {@code review_finding} and writes only proposals, so it spends no money and
     * changes no review — an admin can safely use it to see what the corpus currently supports.
     */
    @POST
    @Path("/rescan")
    public Response rescan() {
        return Response.ok(new RescanResult(proposals.scan())).build();
    }

    public record RescanResult(int proposed) {
    }

    private static Response decided(boolean changed) {
        return changed ? Response.noContent().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    /** Who decided, recorded on the row — an approval changes every future review and needs an owner. */
    private String decider() {
        return identity.isAnonymous() ? "" : identity.getPrincipal().getName();
    }
}
