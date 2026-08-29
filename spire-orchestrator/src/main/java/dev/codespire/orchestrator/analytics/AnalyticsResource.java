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
    public record MyActivity(boolean linked, String providerType, String authorId,
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
            return new MyActivity(false, null, null, null, List.of());
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
    public MyActivity myActivity() {
        Optional<OperatorIdentities.Link> link = identities.firstFor(callerSubject());
        if (link.isEmpty()) {
            return MyActivity.unlinked();
        }
        OperatorIdentities.Link owned = link.get();
        return new MyActivity(true, owned.providerType(), owned.authorId(),
                queries.totalsForAuthor(owned.providerType(), owned.authorId()),
                queries.breakdownForAuthor(owned.providerType(), owned.authorId()));
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
     * The caller's OIDC subject, matching what {@code /api/me} reports.
     *
     * <p>Falls back to the principal name for the same reason that endpoint does — the {@code %dev}
     * profile runs unauthenticated and carries no JWT — so the two never disagree about who the
     * caller is, which would make a mapping created from one unusable by the other.
     */
    private String callerSubject() {
        if (identity.isAnonymous()) {
            return "";
        }
        Object principal = identity.getPrincipal();
        if (principal instanceof io.quarkus.oidc.runtime.OidcJwtCallerPrincipal jwt
                && jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
            return jwt.getSubject();
        }
        return identity.getPrincipal().getName();
    }
}
