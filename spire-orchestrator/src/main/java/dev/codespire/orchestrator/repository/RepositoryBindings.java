package dev.codespire.orchestrator.repository;

import dev.codespire.contract.scm.ForgeOrigin;
import dev.codespire.orchestrator.provider.ProviderRegistry.AccountConflict;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Lock accounts before binding, sharing the provider edit/delete lock so validation cannot race edits. */
@ApplicationScoped
public class RepositoryBindings {
    public void replace(Connection connection, UUID repositoryId, RepositoryInput input) throws SQLException {
        validate(connection, input);
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM repository_account WHERE repository_id = ?")) {
            statement.setObject(1, repositoryId);
            statement.executeUpdate();
        }
        insert(connection, repositoryId, new Binding(input.reviewerAccountId(), "REVIEWER"));
        insert(connection, repositoryId, new Binding(input.factoryAccountId(), "FACTORY"));
    }

    public void validate(Connection connection, RepositoryInput input) throws SQLException {
        String reviewer = validate(connection, input.reviewerAccountId(), new Expected(input, "REVIEWER"));
        String factory = validate(connection, input.factoryAccountId(), new Expected(input, "FACTORY"));
        if (!reviewer.isBlank() && reviewer.equals(factory)) {
            throw new AccountConflict("Reviewer and factory must use different resolved identities");
        }
    }

    private String validate(Connection connection, UUID accountId, Expected expected) throws SQLException {
        if (accountId == null) return "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT type, base_url, role, bot_account_id FROM scm_provider WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, accountId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new AccountConflict("Selected account no longer exists");
                if (!rows.getString("role").equals(expected.role())) throw new AccountConflict("Selected account has the wrong role");
                if (!rows.getString("type").equals(expected.input().scmType())) throw new AccountConflict("Selected account has the wrong forge kind");
                if (!ForgeOrigin.of(rows.getString("base_url")).equals(expected.input().forgeOrigin())) {
                    throw new AccountConflict("Selected account belongs to another forge origin");
                }
                String identity = rows.getString("bot_account_id");
                return identity == null ? "" : identity;
            }
        }
    }

    private void insert(Connection connection, UUID repositoryId, Binding binding) throws SQLException {
        if (binding.accountId() == null) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO repository_account (repository_id,account_id,role) VALUES (?,?,?)")) {
            statement.setObject(1, repositoryId);
            statement.setObject(2, binding.accountId());
            statement.setString(3, binding.role());
            statement.executeUpdate();
        }
    }

    private record Expected(RepositoryInput input, String role) { }
    private record Binding(UUID accountId, String role) { }
}
