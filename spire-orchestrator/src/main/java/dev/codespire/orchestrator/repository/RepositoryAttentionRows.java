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
                SELECT u.registration_id,u.scm_type,u.forge_origin,u.workspace,u.slug FROM repository_unregistered_event u
                WHERE NOT EXISTS (SELECT 1 FROM repository r WHERE r.scm_type=u.scm_type
                  AND r.forge_origin=u.forge_origin AND r.workspace=u.workspace AND r.slug=u.slug)
                ORDER BY u.registration_id,u.workspace,u.slug
                """); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                String registration = results.getString("registration_id");
                String origin = results.getString("forge_origin");
                String workspace = results.getString("workspace");
                String slug = results.getString("slug");
                rows.add(new AttentionView("REPOSITORY_NOT_REGISTERED", AttentionView.Severity.WARNING,
                        workspace + "/" + slug,
                        "Verified event for " + workspace + "/" + slug + " at "
                                + (origin == null ? "an unresolved forge origin" : origin)
                                + " from registration " + registration + ". Register this repository to enable routing.",
                        "/settings/repositories?register=true&registration=" + registration
                                + "&scmType=" + encode(results.getString("scm_type")) + "&forgeOrigin=" + encode(origin)
                                + "&workspace=" + encode(workspace) + "&slug=" + encode(slug)));
            }
        }
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

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
