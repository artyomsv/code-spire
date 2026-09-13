package dev.codespire.orchestrator.repository;

import dev.codespire.contract.event.RepositoryRegistration;
import dev.codespire.contract.scm.ForgeOrigin;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Replayable gateway metadata bridge. Existing/operator-edited bindings are never overwritten. */
@ApplicationScoped
public class RepositoryMigrationBridge {
    @Inject DataSource dataSource;
    @Inject RepositoryRegistry repositories;
    @Inject RepositoryBindings bindings;

    @Transactional
    public void apply(RepositoryRegistration snapshot) {
        try (Connection connection = dataSource.getConnection()) {
            if (!accept(connection, snapshot)) return;
            if (snapshot.deleted() || !"repo".equals(snapshot.scope())) return;
            try (PreparedStatement mapped = connection.prepareStatement(
                    "SELECT repository_id FROM repository_registration_bridge WHERE registration_id=?")) {
                mapped.setObject(1, snapshot.registrationId());
                try (ResultSet rows = mapped.executeQuery()) { if (rows.next() && rows.getObject(1) != null) return; }
            }
            String target = snapshot.target();
            int slash = target.lastIndexOf('/');
            if (slash < 1 || slash == target.length() - 1) {
                finish(connection, snapshot.registrationId(), new Result(null, "invalid_repository_path"));
                return;
            }
            Coordinates coordinates = new Coordinates(snapshot.providerType(), target.substring(0, slash), target.substring(slash + 1));
            finish(connection, snapshot.registrationId(), reconcile(connection, snapshot, coordinates));
        } catch (SQLException failure) { throw new IllegalStateException("Repository snapshot reconciliation failed", failure); }
    }

    private boolean accept(Connection connection, RepositoryRegistration snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO repository_registration_bridge
                  (registration_id,revision,provider_type,forge_origin,scope,target,enabled,deleted)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (registration_id) DO UPDATE SET
                  revision=EXCLUDED.revision,provider_type=EXCLUDED.provider_type,forge_origin=EXCLUDED.forge_origin,
                  scope=EXCLUDED.scope,target=EXCLUDED.target,enabled=EXCLUDED.enabled,deleted=EXCLUDED.deleted,
                  problem=NULL,repository_id=CASE WHEN repository_registration_bridge.provider_type=EXCLUDED.provider_type
                    AND repository_registration_bridge.target=EXCLUDED.target
                    AND repository_registration_bridge.scope=EXCLUDED.scope
                    AND repository_registration_bridge.forge_origin IS NOT DISTINCT FROM EXCLUDED.forge_origin
                    THEN repository_registration_bridge.repository_id ELSE NULL END
                WHERE repository_registration_bridge.revision < EXCLUDED.revision
                """)) {
            statement.setObject(1, snapshot.registrationId()); statement.setLong(2, snapshot.revision());
            statement.setString(3, snapshot.providerType()); statement.setString(4, snapshot.forgeOrigin());
            statement.setString(5, snapshot.scope()); statement.setString(6, snapshot.target());
            statement.setBoolean(7, snapshot.enabled()); statement.setBoolean(8, snapshot.deleted());
            return statement.executeUpdate() == 1;
        }
    }

    private Result reconcile(Connection connection, RepositoryRegistration snapshot, Coordinates coordinates) throws SQLException {
        List<LegacyAccount> accounts = candidates(connection, coordinates);
        List<String> origins = accounts.stream().map(LegacyAccount::origin).distinct().toList();
        if (origins.size() != 1) return new Result(null, origins.isEmpty() ? "legacy_account_missing" : "conflicting_forge_origins");
        String origin = origins.getFirst();
        if (snapshot.forgeOrigin() == null) return new Result(null, "registration_origin_unknown");
        if (!ForgeOrigin.of(snapshot.forgeOrigin()).equals(origin)) {
            return new Result(null, "registration_origin_mismatch");
        }
        UUID existing = existing(connection, coordinates, origin);
        if (existing != null) return new Result(existing, null);
        UUID reviewer = account(accounts, "REVIEWER");
        UUID factory = account(accounts, "FACTORY");
        RepositoryInput input = new RepositoryInput(coordinates.type(), origin, coordinates.workspace(), coordinates.slug(),
                true, reviewer, factory);
        try {
            input = repositories.normalize(input);
            bindings.validate(connection, input);
        } catch (IllegalArgumentException | dev.codespire.orchestrator.provider.ProviderRegistry.AccountConflict invalid) {
            return new Result(null, "legacy_binding_invalid");
        }
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO repository (id,scm_type,forge_origin,workspace,slug,enabled) VALUES (?,?,?,?,?,?)
                ON CONFLICT (scm_type,forge_origin,workspace,slug) DO NOTHING
                """)) {
            statement.setObject(1, id); statement.setString(2, input.scmType());
            statement.setString(3, input.forgeOrigin()); statement.setString(4, input.workspace());
            statement.setString(5, input.slug()); statement.setBoolean(6, input.enabled());
            if (statement.executeUpdate() == 1) bindings.replace(connection, id, input);
            else id = existing(connection, coordinates, origin);
        }
        return new Result(id, null);
    }

    private List<LegacyAccount> candidates(Connection connection, Coordinates coordinates) throws SQLException {
        // Deleted/repurposed accounts cannot regain a binding from the immutable migration snapshot.
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT l.account_id,l.base_url,l.role,p.base_url current_url,p.type current_type,p.role current_role
                FROM repository_legacy_account l JOIN scm_provider p ON p.id=l.account_id
                WHERE l.type=? AND l.workspace=?
                """)) {
            statement.setString(1, coordinates.type()); statement.setString(2, coordinates.workspace());
            List<LegacyAccount> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String origin = ForgeOrigin.of(rows.getString("base_url"));
                    if (coordinates.type().equals(rows.getString("current_type"))
                            && rows.getString("role").equals(rows.getString("current_role"))
                            && origin.equals(ForgeOrigin.of(rows.getString("current_url")))) {
                        result.add(new LegacyAccount(rows.getObject("account_id", UUID.class), origin, rows.getString("role")));
                    }
                }
            }
            return result;
        }
    }

    private UUID existing(Connection connection, Coordinates coordinates, String origin) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM repository WHERE scm_type=? AND forge_origin=? AND workspace=? AND slug=?")) {
            statement.setString(1, coordinates.type()); statement.setString(2, origin);
            statement.setString(3, coordinates.workspace()); statement.setString(4, coordinates.slug());
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getObject(1, UUID.class) : null; }
        }
    }

    private UUID account(List<LegacyAccount> accounts, String role) {
        List<UUID> matches = accounts.stream().filter(account -> account.role().equals(role)).map(LegacyAccount::id).toList();
        if (matches.size() > 1) throw new IllegalStateException("Ambiguous legacy role binding");
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private void finish(Connection connection, UUID registrationId, Result result) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE repository_registration_bridge SET repository_id=?, problem=? WHERE registration_id=?")) {
            statement.setObject(1, result.repositoryId()); statement.setString(2, result.problem());
            statement.setObject(3, registrationId); statement.executeUpdate();
        }
    }

    private record Coordinates(String type, String workspace, String slug) { }
    private record LegacyAccount(UUID id, String origin, String role) { }
    private record Result(UUID repositoryId, String problem) { }
}
