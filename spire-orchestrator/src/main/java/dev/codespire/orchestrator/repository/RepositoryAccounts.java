package dev.codespire.orchestrator.repository;

import dev.codespire.contract.scm.ForgeOrigin;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ScmProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Sole pipeline credential selector: repository id, explicit role binding, compatible enabled account. */
@ApplicationScoped
public class RepositoryAccounts {
    @Inject DataSource dataSource;
    @Inject ProviderRegistry providers;

    /** Non-secret configured account, including disabled accounts, for the serving view. */
    public Optional<dev.codespire.orchestrator.provider.ProviderView> registration(UUID repositoryId, ProviderRole role) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT account_id FROM repository_account WHERE repository_id=? AND role=?")) {
            statement.setObject(1, repositoryId);
            statement.setString(2, role.name());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? providers.get(rows.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot read selected repository account", failure);
        }
    }

    public Optional<ScmProvider> resolve(UUID repositoryId, ProviderRole role) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT a.account_id, r.scm_type, r.forge_origin, peer.bot_account_id peer_identity FROM repository r
                JOIN repository_account a ON a.repository_id = r.id
                LEFT JOIN repository_account other ON other.repository_id=r.id AND other.role<>a.role
                LEFT JOIN scm_provider peer ON peer.id=other.account_id
                WHERE r.id = ? AND a.role = ? AND r.enabled = TRUE
                ORDER BY a.role
                """)) {
            statement.setObject(1, repositoryId);
            statement.setString(2, role.name());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                String type = rows.getString("scm_type");
                String origin = rows.getString("forge_origin");
                String peerIdentity = rows.getString("peer_identity");
                return providers.resolveById(rows.getObject("account_id", UUID.class))
                        .filter(ScmProvider::enabled)
                        .filter(account -> account.role() == role)
                        .filter(account -> account.type().equals(type))
                        .filter(account -> peerIdentity == null || peerIdentity.isBlank() || !peerIdentity.equals(account.botAccountId()))
                        .filter(account -> ForgeOrigin.of(account.baseUrl()).equals(origin));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot resolve repository account", failure);
        }
    }
}
