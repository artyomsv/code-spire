package dev.codespire.orchestrator.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    @Inject ObjectMapper mapper;

    /** Legacy blank origins mean unknown; new wire records still reject present-but-blank values. */
    @Transactional
    public void applyLegacyPayload(String payload) throws JsonProcessingException {
        JsonNode document = mapper.readTree(payload);
        JsonNode origin = document.get("forgeOrigin");
        if (origin != null && origin.isTextual() && missingOrigin(origin.textValue())) {
            ((ObjectNode) document).putNull("forgeOrigin");
        }
        apply(mapper.treeToValue(document, RepositoryRegistration.class));
    }

    private static boolean missingOrigin(String origin) {
        return origin == null || origin.isBlank();
    }

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
        if (missingOrigin(snapshot.forgeOrigin())) return new Result(null, "registration_origin_unknown");
        UUID existing = existing(connection, coordinates, ForgeOrigin.of(snapshot.forgeOrigin()));
        return new Result(existing, existing == null ? "repository_not_registered" : null);
    }

    private UUID existing(Connection connection, Coordinates coordinates, String origin) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM repository WHERE scm_type=? AND forge_origin=? AND workspace=? AND slug=?")) {
            statement.setString(1, coordinates.type()); statement.setString(2, origin);
            statement.setString(3, coordinates.workspace()); statement.setString(4, coordinates.slug());
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getObject(1, UUID.class) : null; }
        }
    }

    private void finish(Connection connection, UUID registrationId, Result result) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE repository_registration_bridge SET repository_id=?, problem=? WHERE registration_id=?")) {
            statement.setObject(1, result.repositoryId()); statement.setString(2, result.problem());
            statement.setObject(3, registrationId); statement.executeUpdate();
        }
    }

    private record Coordinates(String type, String workspace, String slug) { }
    private record Result(UUID repositoryId, String problem) { }
}
