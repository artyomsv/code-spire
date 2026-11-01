package dev.codespire.orchestrator.repository;

import dev.codespire.contract.scm.ForgeOrigin;
import dev.codespire.orchestrator.llm.LlmProviderRegistry;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ProviderRegistry.AccountConflict;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Repository configuration, separate from the gateway's registration and credential stores. */
@ApplicationScoped
public class RepositoryRegistry {
    private static final String SELECT = """
            SELECT r.*, a.id reviewer_id, a.name reviewer_name, a.bot_username reviewer_handle,
                   a.enabled reviewer_enabled, a.bot_account_id reviewer_identity,
                   b.id factory_id, b.name factory_name, b.bot_username factory_handle,
                   b.enabled factory_enabled, b.bot_account_id factory_identity
            FROM repository r
            LEFT JOIN repository_account ra ON ra.repository_id=r.id AND ra.role='REVIEWER'
            LEFT JOIN scm_provider a ON a.id=ra.account_id
            LEFT JOIN repository_account rb ON rb.repository_id=r.id AND rb.role='FACTORY'
            LEFT JOIN scm_provider b ON b.id=rb.account_id
            """;
    private static final String FIND = SELECT + """
            WHERE r.scm_type=? AND r.forge_origin=? AND r.workspace=? AND r.slug=?
            """;
    @Inject DataSource dataSource;
    @Inject RepositoryBindings bindings;
    @Inject LlmProviderRegistry llmProviders;

    public List<RepositoryView> list() {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                SELECT + " ORDER BY r.forge_origin,r.workspace,r.slug"); ResultSet rows = statement.executeQuery()) {
            List<RepositoryView> result = new ArrayList<>();
            while (rows.next()) result.add(view(rows));
            return result;
        } catch (SQLException failure) { throw database(failure); }
    }

    public Optional<RepositoryView> get(UUID id) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(SELECT + " WHERE r.id=?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(view(rows)) : Optional.empty(); }
        } catch (SQLException failure) { throw database(failure); }
    }

    /** Full forge identity lookup for the resolver cutover; never an account-workspace fallback. */
    public Optional<RepositoryView> find(String scmType, String forgeOrigin, String workspace, String slug) {
        // Legacy review addresses split nested GitLab paths at the first slash. The registry owns
        // the full namespace and a leaf slug; normalize the path without changing review ids/AADs.
        String fullPath = workspace + "/" + slug;
        int leaf = fullPath.lastIndexOf('/');
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(FIND)) {
            statement.setString(1, scmType);
            statement.setString(2, ForgeOrigin.of(forgeOrigin));
            statement.setString(3, fullPath.substring(0, leaf));
            statement.setString(4, fullPath.substring(leaf + 1));
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(view(rows)) : Optional.empty(); }
        } catch (SQLException failure) { throw database(failure); }
    }

    @Transactional
    public RepositoryView create(RepositoryInput raw) {
        RepositoryInput input = normalize(raw);
        requireReviewProvider(input);
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            insert(connection, id, input);
            bindings.replace(connection, id, input);
        } catch (SQLException failure) {
            if ("23505".equals(failure.getSQLState())) throw new AccountConflict("Repository is already registered at this forge origin");
            throw referenceGone(failure);
        }
        return get(id).orElseThrow();
    }

    @Transactional
    public RepositoryView update(UUID id, long expectedRevision, RepositoryInput raw) {
        RepositoryInput input = normalize(raw);
        requireReviewProvider(input);
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE repository SET enabled=?, reviewer_may_approve=?, review_llm_provider_id=?, revision=revision+1
                WHERE id=? AND revision=? AND scm_type=? AND forge_origin=? AND workspace=? AND slug=?
                """)) {
            statement.setBoolean(1, input.enabled());
            statement.setBoolean(2, input.reviewerMayApprove());
            statement.setObject(3, input.reviewLlmProviderId(), Types.OTHER);
            statement.setObject(4, id);
            statement.setLong(5, expectedRevision);
            statement.setString(6, input.scmType());
            statement.setString(7, input.forgeOrigin());
            statement.setString(8, input.workspace());
            statement.setString(9, input.slug());
            if (statement.executeUpdate() != 1) throw new AccountConflict("Repository changed or its coordinates differ; reload before saving");
            bindings.replace(connection, id, input);
        } catch (SQLException failure) { throw referenceGone(failure); }
        return get(id).orElseThrow();
    }

    private void insert(Connection connection, UUID id, RepositoryInput input) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO repository (id,scm_type,forge_origin,workspace,slug,enabled,reviewer_may_approve,review_llm_provider_id) VALUES (?,?,?,?,?,?,?,?)")) {
            statement.setObject(1, id);
            statement.setString(2, input.scmType());
            statement.setString(3, input.forgeOrigin());
            statement.setString(4, input.workspace());
            statement.setString(5, input.slug());
            statement.setBoolean(6, input.enabled());
            statement.setBoolean(7, input.reviewerMayApprove());
            statement.setObject(8, input.reviewLlmProviderId(), Types.OTHER);
            statement.executeUpdate();
        }
    }

    public RepositoryInput normalize(RepositoryInput input) {
        if (input == null || input.scmType() == null || !ProviderClients.SUPPORTED_TYPES.contains(input.scmType())) throw new IllegalArgumentException("Select a supported forge kind");
        String workspace = path(input.workspace());
        String slug = path(input.slug());
        if (slug.contains("/")) throw new IllegalArgumentException("Repository slug cannot contain a slash");
        return new RepositoryInput(input.scmType(), ForgeOrigin.of(input.forgeOrigin()), workspace, slug,
                input.enabled() == null || input.enabled(), input.reviewerAccountId(), input.factoryAccountId(),
                input.reviewerMayApprove() == null || input.reviewerMayApprove(), input.reviewLlmProviderId());
    }

    /** Looked up before any write, so an id naming no provider is the caller's mistake (400), not a key violation. */
    private void requireReviewProvider(RepositoryInput input) {
        UUID provider = input.reviewLlmProviderId();
        if (provider != null && llmProviders.get(provider).isEmpty()) {
            throw new IllegalArgumentException("LLM provider " + provider + " does not exist; choose one listed in Settings → LLM");
        }
    }

    /** The provider a repository's reviews use (harness reviews, decision 1); empty means the global default. */
    public Optional<UUID> reviewLlmProviderId(UUID id) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT review_llm_provider_id FROM repository WHERE id=?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(rows.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException failure) { throw database(failure); }
    }

    /**
     * The harness a repository's reviews run on (harness reviews, step 5); empty means the API.
     *
     * <p>Not built yet: the red phase of step 5 declares the read so its callers and tests compile.
     */
    public Optional<ReviewHarness> reviewHarness(UUID repositoryId) {
        return Optional.empty();
    }

    /** The setting for the saga (ADR-047 §4.1); empty when the row is missing, never a default. */
    public Optional<Boolean> reviewerMayApprove(UUID id) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT reviewer_may_approve FROM repository WHERE id=?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(rows.getBoolean(1)) : Optional.empty(); }
        } catch (SQLException failure) { throw database(failure); }
    }

    private String path(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Workspace and repository slug are required");
        String result = value.trim();
        for (String part : result.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.contains("\\")
                    || part.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid repository path");
        }
        return result;
    }

    private RepositoryView view(ResultSet rows) throws SQLException {
        return new RepositoryView(rows.getObject("id", UUID.class), rows.getString("scm_type"), rows.getString("forge_origin"),
                rows.getString("workspace"), rows.getString("slug"), rows.getBoolean("enabled"), rows.getLong("revision"),
                account(rows, "reviewer"), account(rows, "factory"), rows.getBoolean("reviewer_may_approve"),
                rows.getObject("review_llm_provider_id", UUID.class));
    }

    private RepositoryView.Account account(ResultSet rows, String role) throws SQLException {
        UUID id = rows.getObject(role + "_id", UUID.class);
        if (id == null) return null;
        String handle = rows.getString(role + "_handle");
        String identity = rows.getString(role + "_identity");
        String peerIdentity = rows.getString(("reviewer".equals(role) ? "factory" : "reviewer") + "_identity");
        String state = !rows.getBoolean(role + "_enabled") ? "disabled"
                : identity != null && !identity.isBlank() && identity.equals(peerIdentity) ? "identity-conflict"
                : "factory".equals(role) && (handle == null || handle.isBlank()) ? "no-login"
                : "reviewer".equals(role) && (identity == null || identity.isBlank()) ? "no-identity" : "configured";
        return new RepositoryView.Account(id, rows.getString(role + "_name"), role.toUpperCase(java.util.Locale.ROOT), handle, state);
    }

    /** A provider deleted between the lookup and the write is a stale form, not a server fault. */
    private RuntimeException referenceGone(SQLException failure) {
        String message = failure.getMessage();
        if ("23503".equals(failure.getSQLState()) && message != null && message.contains("review_llm_provider_id")) {
            return new IllegalArgumentException("The selected LLM provider no longer exists; reload before saving");
        }
        return database(failure);
    }

    private IllegalStateException database(SQLException failure) {
        return new IllegalStateException("Repository registry operation failed", failure);
    }
}
