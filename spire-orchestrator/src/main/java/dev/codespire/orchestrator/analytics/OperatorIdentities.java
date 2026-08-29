package dev.codespire.orchestrator.analytics;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which SCM account an operator is (P4 / FR-11).
 *
 * <p>Per-author analytics is self-visible: an operator sees their own numbers, an admin sees anyone's.
 * That needs a link between the OIDC subject a session carries and the {@code providerUserId} the SCM
 * puts on a pull request, and nothing in the system had one — {@code provider_author} is a per-SCM
 * allowlist of who may be reviewed, not a map of who an operator is.
 *
 * <p><b>Admin-managed, and inferring the link from usernames is refused rather than deferred.</b> A
 * coincidental match between an OIDC {@code preferred_username} and an SCM handle would show one
 * person another person's performance data, and nothing in the UI would look wrong — a silent
 * failure about a named individual. ADR-022 made cookie scoping a real mechanism instead of a
 * convention for the same class of reason.
 */
@ApplicationScoped
public class OperatorIdentities {

    private static final Logger LOG = Logger.getLogger(OperatorIdentities.class);

    /**
     * One operator's SCM account on one platform.
     *
     * <p>{@code providerType} is not decoration: a bare {@code providerUserId} is not a person. The
     * same numeric id on GitHub and GitLab belongs to two unrelated humans, and this project has
     * already been bitten twice by treating a workspace name as unique across platforms.
     */
    public record Link(String oidcSubject, String providerType, String authorId) {
    }

    @Inject
    DataSource dataSource;

    /** Every mapping. Admin-only at the resource: this is a map from real people to their activity. */
    public List<Link> all() {
        List<Link> links = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT oidc_subject, provider_type, author_id FROM operator_identity"
                             + " ORDER BY oidc_subject, provider_type");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                links.add(new Link(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read operator identities", e);
        }
        return links;
    }

    /** The SCM accounts one operator owns — the set a self-visible request is allowed to read. */
    public List<Link> forSubject(String oidcSubject) {
        if (oidcSubject == null || oidcSubject.isBlank()) {
            return List.of();
        }
        List<Link> links = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT oidc_subject, provider_type, author_id FROM operator_identity"
                             + " WHERE oidc_subject = ? ORDER BY provider_type")) {
            ps.setString(1, oidcSubject);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    links.add(new Link(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        } catch (SQLException e) {
            // Denies, and says so honestly. An empty list is the right AUTHORIZATION answer — failing
            // open would be a privacy failure rather than an outage — but it is the wrong thing to
            // REPORT, so this throws a marked exception the read path turns into an error state.
            // The screen otherwise told an operator to ask an admin for a link they already have.
            LOG.warnf(e, "Could not resolve operator identities for a caller");
            throw new IdentityLookupFailed(e);
        }
        return links;
    }

    /** The lookup could not run. Distinct from "this operator has no mapping", which is a fact. */
    public static final class IdentityLookupFailed extends RuntimeException {
        IdentityLookupFailed(Throwable cause) {
            super("could not resolve operator identities", cause);
        }
    }

    /**
     * Whether this caller owns that SCM identity.
     *
     * <p>The whole authorization rule for a self-visible read, and it is enforced here rather than by
     * {@code @RolesAllowed}: "a viewer may read their own row" is row-level, which an annotation
     * cannot express. An unlinked operator matches nothing and is refused — never defaulted into
     * someone else's numbers.
     */
    public boolean owns(String oidcSubject, String providerType, String authorId) {
        try {
            return forSubject(oidcSubject).stream()
                    .anyMatch(link -> link.providerType().equalsIgnoreCase(providerType)
                            && link.authorId().equals(authorId));
        } catch (IdentityLookupFailed e) {
            // Authorization fails CLOSED even though the read path reports the fault: refusing a
            // legitimate operator during an outage is recoverable, showing one person another
            // person's performance data is not.
            return false;
        }
    }

    /** The first mapping for a caller, for the dashboard's "my activity" landing view. */
    public Optional<Link> firstFor(String oidcSubject) {
        return forSubject(oidcSubject).stream().findFirst();
    }

    public void link(Link link) {
        String sql = """
                INSERT INTO operator_identity (oidc_subject, provider_type, author_id)
                VALUES (?, ?, ?)
                ON CONFLICT (oidc_subject, provider_type)
                DO UPDATE SET author_id = EXCLUDED.author_id
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, link.oidcSubject());
            ps.setString(2, link.providerType());
            ps.setString(3, link.authorId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not link the operator identity", e);
        }
    }

    public boolean unlink(String oidcSubject, String providerType) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM operator_identity WHERE oidc_subject = ? AND provider_type = ?")) {
            ps.setString(1, oidcSubject);
            ps.setString(2, providerType);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("could not unlink the operator identity", e);
        }
    }
}
