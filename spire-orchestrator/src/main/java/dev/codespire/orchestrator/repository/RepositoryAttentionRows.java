package dev.codespire.orchestrator.repository;

import dev.codespire.contract.attention.AttentionView;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Migration problems are current conditions; fixing the explicit mapping removes the row. */
public final class RepositoryAttentionRows {
    private RepositoryAttentionRows() { }

    public static void collect(Connection connection, List<AttentionView> rows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT registration_id,provider_type,forge_origin,target,problem FROM repository_registration_bridge
                WHERE problem IS NOT NULL AND deleted=FALSE ORDER BY registration_id
                """); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                String id = results.getString("registration_id");
                String origin = results.getString("forge_origin");
                rows.add(new AttentionView("REPOSITORY_MAPPING_PENDING", AttentionView.Severity.WARNING, id,
                        results.getString("provider_type") + " " + results.getString("target") + " ("
                                + (origin == null ? "origin unresolved" : origin) + "): " + results.getString("problem")
                                + ". Select the repository and accounts for registration " + id + ".",
                        "/settings/repositories/registry?registration=" + id));
            }
        }
    }
}
