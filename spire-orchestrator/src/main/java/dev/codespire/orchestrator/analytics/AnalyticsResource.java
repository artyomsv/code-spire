package dev.codespire.orchestrator.analytics;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Optional;

/**
 * The analytics read surface (P4 / FR-11).
 *
 * <p>Plain REST under {@code /api} — ADR-022 scopes this service's session cookie to that prefix, and
 * nothing here needs live push: the reviews socket already carries the only genuinely live state.
 */
@Path("/api/analytics")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"spire-viewer", "spire-admin"})
public class AnalyticsResource {

    private static final String ADMIN = "spire-admin";

    /** What a caller may see about themselves, and why not, when they may not. */
    public record MyActivity(boolean linked, List<OperatorIdentities.Link> identities,
                             AnalyticsQueries.Totals totals,
                             List<AnalyticsQueries.Breakdown> breakdown) {

        /**
         * The unlinked answer.
         *
         * <p>Deliberately distinct from an empty result. An empty chart reads as "you have done
         * nothing"; this reads as "we do not know who you are" — three different states (empty,
         * error, unlinked) that send an operator to three different places, which is the lesson the
         * ADR-025 {@code refused} incident charged for when a missing case defaulted into the
         * reassuring branch.
         */
        static MyActivity unlinked() {
            return new MyActivity(false, List.of(), null, List.of());
        }
    }

    /** One lens's numbers plus its by-kind breakdown. */
    public record Lens(AnalyticsQueries.Totals totals, List<AnalyticsQueries.Breakdown> breakdown) {
    }

    @Inject
    AnalyticsQueries queries;

    @Inject
    OperatorIdentities identities;

    @Inject
    SecurityIdentity identity;

    @GET
    @Path("/repos")
    public List<String> repositories() {
        return queries.repositories();
    }

    @GET
    @Path("/repos/{workspace}/{slug}")
    public Lens repository(@PathParam("workspace") String workspace, @PathParam("slug") String slug) {
        return new Lens(queries.totalsForRepo(workspace, slug), queries.breakdownForRepo(workspace, slug));
    }

    /** Deployment-wide totals, for the analytics index. */
    @GET
    @Path("/overview")
    public Lens overview() {
        return new Lens(queries.totalsForRepo(null, null), queries.breakdownForRepo(null, null));
    }

    /**
     * The caller's own activity, or an explicit unlinked state.
     *
     * <p>No path parameter on purpose: a caller cannot ask about a subject other than their own here,
     * so there is no identity to get wrong.
     */
    @GET
    @Path("/me")
    public Response myActivity() {
        try {
            return Response.ok(resolveMyActivity()).build();
        } catch (OperatorIdentities.IdentityLookupFailed e) {
            // An outage is not "you are not linked". Reporting it as unlinked told an operator to go
            // and ask an admin for a mapping they may already have -- a wrong instruction, which is
            // worse than no answer. 503 lands in the UI's error branch, which is its own sentence.
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        }
    }

    /**
     * Every SCM account the caller owns, aggregated.
     *
     * <p>Not the first one. A developer is routinely a GitHub id, a GitLab id and a Bitbucket UUID
     * at once, and reporting one of them showed an arbitrary slice of their work under the heading
     * "my activity" — which is worse than showing nothing, because it looks complete.
     */
    private MyActivity resolveMyActivity() {
        List<OperatorIdentities.Link> owned = identities.forSubject(callerSubject());
        if (owned.isEmpty()) {
            return MyActivity.unlinked();
        }
        return new MyActivity(true, owned, queries.totalsForIdentities(owned),
                queries.breakdownForIdentities(owned));
    }

    /**
     * Any author's activity — <b>row-level authorization, enforced here rather than by annotation.</b>
     *
     * <p>{@code @RolesAllowed} is ADR-022's stated control and cannot express "a viewer may read their
     * own row". So the rule lives in code: an admin may read anyone, and everyone else may read only
     * an identity they are mapped to. An unmapped operator matches nothing and is refused, never
     * defaulted into somebody else's numbers.
     */
    @GET
    @Path("/authors/{providerType}/{authorId}")
    public Response author(@PathParam("providerType") String providerType,
                           @PathParam("authorId") String authorId) {
        if (!identity.hasRole(ADMIN) && !identities.owns(callerSubject(), providerType, authorId)) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return Response.ok(new Lens(queries.totalsForAuthor(providerType, authorId),
                queries.breakdownForAuthor(providerType, authorId))).build();
    }

    /**
     * The caller's OIDC subject — the same resolution {@code /api/me} reports.
     *
     * <p>Shared rather than copied, because the two must agree: a mapping created from one
     * spelling of "who is this" and authorized against another leaves every operator unlinked, and
     * nothing on screen would explain why.
     */
    private String callerSubject() {
        return dev.codespire.orchestrator.security.OidcSubjects.of(identity);
    }
}
