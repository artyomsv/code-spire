package dev.codespire.orchestrator.work;

import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.ForgeOrigin;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.provider.*;
import dev.codespire.worksource.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

@ApplicationScoped
public class WorkSourceRegistry {
    @Inject DataSource dataSource;
    @Inject ProviderRegistry providers;
    @Inject ProviderClients clients;

    public record Version(long source, long repository, long account) {}
    /**
     * One allowlisted person as the tracker showed them when they were confirmed. The id is the
     * authority; the handle and display name exist so an operator recognises who they allowed.
     */
    public record Person(String providerUserId, String handle, String displayName) {}
    public record Source(UUID id, String name, WorkSourceType type, String origin, String projectId, String scope,
                         UUID repositoryId, UUID accountId, boolean enabled, boolean configuredEnabled, Version version, String cursor,
                         String health, ScmType scm, String forgeOrigin, RepoRef repository, List<Person> allowedPeople) {
        public Source { allowedPeople = List.copyOf(allowedPeople); }

        /**
         * The ids that authorise labels and tracker answers. Derived from the people rather than stored
         * beside them, so the list an operator reads and the set that decides cannot drift apart.
         */
        public Set<String> allowedActors() {
            Set<String> ids = new HashSet<>();
            for (Person person : allowedPeople) ids.add(person.providerUserId());
            return Set.copyOf(ids);
        }
    }
    private static final String SELECT = """
            SELECT s.*,r.scm_type,r.forge_origin,r.workspace,r.slug,r.revision repository_revision,
                   a.revision account_revision,(s.enabled AND r.enabled AND a.enabled) usable
            FROM work_source s JOIN repository r ON r.id=s.repository_id JOIN scm_provider a ON a.id=s.account_id
            WHERE s.id=?
            """;

    public Optional<Source> get(UUID id) {
        try (Connection c = dataSource.getConnection()) { return get(c, id, false); }
        catch (SQLException failure) { throw database(failure); }
    }

    Optional<Source> get(Connection c, UUID id, boolean lock) throws SQLException {
        // Full literal variants keep the lock boundary and SQL vocabulary structural.
        String sql = lock ? SELECT_LOCKED : SELECT;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Source(id, rs.getString("name"), WorkSourceType.valueOf(rs.getString("type")),
                        rs.getString("origin"), rs.getString("external_project_id"), rs.getString("external_scope"),
                        rs.getObject("repository_id", UUID.class), rs.getObject("account_id", UUID.class), rs.getBoolean("usable"), rs.getBoolean("enabled"),
                        new Version(rs.getLong("revision"), rs.getLong("repository_revision"), rs.getLong("account_revision")),
                        rs.getString("scan_cursor"), rs.getString("health"), ScmType.fromProviderType(rs.getString("scm_type")).orElseThrow(),
                        rs.getString("forge_origin"), new RepoRef(rs.getString("workspace"), rs.getString("slug")), actors(c, id)));
            }
        }
    }
    private static final String SELECT_LOCKED = """
            SELECT s.*,r.scm_type,r.forge_origin,r.workspace,r.slug,r.revision repository_revision,
                   a.revision account_revision,(s.enabled AND r.enabled AND a.enabled) usable
            FROM work_source s JOIN repository r ON r.id=s.repository_id JOIN scm_provider a ON a.id=s.account_id
            WHERE s.id=? FOR UPDATE OF s,r,a
            """;

    public List<Source> list() {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT id FROM work_source ORDER BY name,id");
             ResultSet rs = ps.executeQuery()) {
            List<Source> result = new ArrayList<>();
            while (rs.next()) get(c, rs.getObject(1, UUID.class), false).ifPresent(result::add);
            return result;
        } catch (SQLException failure) { throw database(failure); }
    }

    public WorkSource client(Source source) {
        if (!source.enabled()) throw new WorkSourceException("The work source, repository or account is disabled.");
        ScmProvider account = providers.resolveById(source.accountId()).orElseThrow();
        if (!account.enabled() || !clients.compatibleWorkAccount(source.type(), account)
                || !source.origin().equals(ForgeOrigin.of(account.baseUrl())))
            throw new WorkSourceException("The selected work-source account is unavailable or incompatible.");
        return clients.workSource(source.type(), account, source.projectId(), source.scope());
    }

    private List<Person> actors(Connection c, UUID source) throws SQLException {
        // Ordered: a list is compared by position, so two reads of an unchanged allowlist must agree.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT actor_id,observed_handle,display_name FROM work_source_actor WHERE source_id=? ORDER BY observed_handle,actor_id")) {
            ps.setObject(1, source);
            try (ResultSet rs = ps.executeQuery()) {
                List<Person> result = new ArrayList<>();
                while (rs.next()) result.add(new Person(rs.getString(1), rs.getString(2), rs.getString(3)));
                return result;
            }
        }
    }

    public void health(UUID source, String health) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE work_source SET health=?,checked_at=now() WHERE id=?")) {
            ps.setString(1, health); ps.setObject(2, source); ps.executeUpdate();
        } catch (SQLException failure) { throw database(failure); }
    }

    static IllegalStateException database(SQLException failure) { return new IllegalStateException("Work-source storage failed", failure); }
}
