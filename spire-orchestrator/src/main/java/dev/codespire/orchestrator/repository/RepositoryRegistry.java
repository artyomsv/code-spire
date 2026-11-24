package dev.codespire.orchestrator.repository;

import dev.codespire.contract.scm.ForgeOrigin;
import dev.codespire.contract.work.PayWith;
import dev.codespire.contract.work.ThinkingLevel;
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
    @Inject RepositoryReviewWith reviewWith;

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
        reviewWith.require(input, RepositoryReviewWith.Stored.NONE);
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
        // Read inside this transaction: an unchanged pair is the stored one, not a new choice.
        reviewWith.require(input, new RepositoryReviewWith.Stored(reviewLlmChoice(id).orElse(ReviewLlmChoice.NONE),
                reviewHarness(id).orElse(null)));
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE repository SET enabled=?, reviewer_may_approve=?, review_llm_provider_id=?, review_llm_model=?,
                       review_harness=?, review_harness_model=?, review_harness_effort=?, review_harness_pay_with=?,
                       block_approval_at=COALESCE(?, block_approval_at), verdict_labels=COALESCE(?, verdict_labels),
                       start_review=COALESCE(?, start_review), revision=revision+1
                WHERE id=? AND revision=? AND scm_type=? AND forge_origin=? AND workspace=? AND slug=?
                """)) {
            statement.setBoolean(1, input.enabled());
            statement.setBoolean(2, input.reviewerMayApprove());
            statement.setObject(3, input.reviewLlmProviderId(), Types.OTHER);
            statement.setString(4, input.reviewLlmModel());
            setReviewHarness(statement, 5, input.reviewHarness());
            statement.setString(9, raw.blockApprovalAt() == null ? null : input.blockApprovalAt()); // null: keep the stored level
            statement.setObject(10, input.verdictLabels(), Types.BOOLEAN); // null: keep the stored setting
            statement.setString(11, input.startReview()); // null: keep the stored setting (V121)
            statement.setObject(12, id);
            statement.setLong(13, expectedRevision);
            setCoordinates(statement, input);
            if (statement.executeUpdate() != 1) throw new AccountConflict("Repository changed or its coordinates differ; reload before saving");
            bindings.replace(connection, id, input);
        } catch (SQLException failure) { throw referenceGone(failure); }
        return get(id).orElseThrow();
    }

    /** The coordinates an update is guarded on, parameters 14 to 17: a form for another repository changes nothing. */
    private static void setCoordinates(PreparedStatement statement, RepositoryInput input) throws SQLException {
        statement.setString(14, input.scmType());
        statement.setString(15, input.forgeOrigin());
        statement.setString(16, input.workspace());
        statement.setString(17, input.slug());
    }

    private void insert(Connection connection, UUID id, RepositoryInput input) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO repository (id,scm_type,forge_origin,workspace,slug,enabled,reviewer_may_approve,review_llm_provider_id,"
                        + "review_llm_model,review_harness,review_harness_model,review_harness_effort,review_harness_pay_with,"
                        + "block_approval_at,verdict_labels,start_review)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setObject(1, id);
            statement.setString(2, input.scmType());
            statement.setString(3, input.forgeOrigin());
            statement.setString(4, input.workspace());
            statement.setString(5, input.slug());
            statement.setBoolean(6, input.enabled());
            statement.setBoolean(7, input.reviewerMayApprove());
            statement.setObject(8, input.reviewLlmProviderId(), Types.OTHER);
            statement.setString(9, input.reviewLlmModel());
            setReviewHarness(statement, 10, input.reviewHarness());
            statement.setString(14, input.blockApprovalAt());
            statement.setBoolean(15, Boolean.TRUE.equals(input.verdictLabels())); // null: off (V120)
            statement.setString(16, input.startReview() == null ? StartReview.DEFAULT.name() : input.startReview());
            statement.executeUpdate();
        }
    }

    /** The four harness columns from {@code first} on; a null harness writes four nulls, which is "review on the API". */
    private static void setReviewHarness(PreparedStatement statement, int first, ReviewHarness harness) throws SQLException {
        statement.setString(first, harness == null ? null : harness.harness());
        statement.setString(first + 1, harness == null ? null : harness.model());
        statement.setString(first + 2, harness == null ? null : harness.effort());
        statement.setString(first + 3, harness == null ? null : harness.payWith());
    }

    public RepositoryInput normalize(RepositoryInput input) {
        if (input == null || input.scmType() == null || !ProviderClients.SUPPORTED_TYPES.contains(input.scmType())) throw new IllegalArgumentException("Select a supported forge kind");
        String workspace = RepositoryPaths.path(input.workspace());
        String slug = RepositoryPaths.path(input.slug());
        if (slug.contains("/")) throw new IllegalArgumentException("Repository slug cannot contain a slash");
        return new RepositoryInput(input.scmType(), ForgeOrigin.of(input.forgeOrigin()), workspace, slug,
                input.enabled() == null || input.enabled(), input.reviewerAccountId(), input.factoryAccountId(),
                input.reviewerMayApprove() == null || input.reviewerMayApprove(), input.reviewLlmProviderId(),
                strip(input.reviewLlmModel()), normalizeHarness(input.reviewHarness()),
                BlockApprovalAt.parse(input.blockApprovalAt()).name(), input.verdictLabels(), StartReview.normalize(input.startReview()));
    }

    /** Stripped, the level and the payment in their stored forms; an unknown payment or a malformed level is refused. */
    private static ReviewHarness normalizeHarness(ReviewHarness harness) {
        if (harness == null) return null;
        return new ReviewHarness(strip(harness.harness()), strip(harness.model()),
                ThinkingLevel.normalise(harness.effort()), PayWith.normalise(harness.payWith()));
    }

    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** The provider a repository's reviews use (harness reviews, decision 1); empty means the global default. */
    public Optional<UUID> reviewLlmProviderId(UUID id) {
        return reviewLlmChoice(id).map(ReviewLlmChoice::providerId);
    }

    /** A repository's own API choice: the provider and the model it reviews with, read together. */
    public record ReviewLlmChoice(UUID providerId, String model) {
        /** No stored choice: a new repository, or one on the global default. Never equal to a choice naming a provider. */
        static final ReviewLlmChoice NONE = new ReviewLlmChoice(null, null);
    }

    /**
     * The provider and model a repository's reviews use, in one read so the two never come from different saves;
     * empty means the global default (no provider named, or no such row).
     */
    public Optional<ReviewLlmChoice> reviewLlmChoice(UUID id) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT review_llm_provider_id, review_llm_model FROM repository WHERE id=?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                UUID provider = rows.getObject(1, UUID.class);
                return provider == null ? Optional.empty() : Optional.of(new ReviewLlmChoice(provider, rows.getString(2)));
            }
        } catch (SQLException failure) { throw database(failure); }
    }

    /** The harness a repository's reviews run on (harness reviews, step 5); empty means the API, or no such row. */
    public Optional<ReviewHarness> reviewHarness(UUID repositoryId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT review_harness, review_harness_model, review_harness_effort, review_harness_pay_with"
                        + " FROM repository WHERE id=?")) {
            statement.setObject(1, repositoryId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(harnessOf(rows)) : Optional.empty();
            }
        } catch (SQLException failure) { throw database(failure); }
    }

    /** The row's harness, or null when it reviews on the API: V109 keeps the four columns whole or absent. */
    private static ReviewHarness harnessOf(ResultSet rows) throws SQLException {
        String harness = rows.getString("review_harness");
        if (harness == null) return null;
        return new ReviewHarness(harness, rows.getString("review_harness_model"), rows.getString("review_harness_effort"),
                rows.getString("review_harness_pay_with"));
    }

    /** The setting for the saga (ADR-047 §4.1); empty when the row is missing, never a default. */
    public Optional<Boolean> reviewerMayApprove(UUID id) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT reviewer_may_approve FROM repository WHERE id=?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(rows.getBoolean(1)) : Optional.empty(); }
        } catch (SQLException failure) { throw database(failure); }
    }

    /** Whether the worker labels each posted review from its verdict (V120); empty when the row is missing. */
    public Optional<Boolean> verdictLabels(UUID repositoryId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT verdict_labels FROM repository WHERE id=?")) {
            statement.setObject(1, repositoryId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(rows.getBoolean(1)) : Optional.empty(); }
        } catch (SQLException failure) { throw database(failure); }
    }

    /** The level for the verdict rule and the land gate (V115); empty when the row is missing, never a default. */
    public Optional<BlockApprovalAt> blockApprovalAt(UUID repositoryId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT block_approval_at FROM repository WHERE id=?")) {
            statement.setObject(1, repositoryId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(BlockApprovalAt.valueOf(rows.getString(1))) : Optional.empty();
            }
        } catch (SQLException failure) { throw database(failure); }
    }

    private RepositoryView view(ResultSet rows) throws SQLException {
        return new RepositoryView(rows.getObject("id", UUID.class), rows.getString("scm_type"), rows.getString("forge_origin"),
                rows.getString("workspace"), rows.getString("slug"), rows.getBoolean("enabled"), rows.getLong("revision"),
                account(rows, "reviewer"), account(rows, "factory"), rows.getBoolean("reviewer_may_approve"),
                rows.getObject("review_llm_provider_id", UUID.class), rows.getString("review_llm_model"), harnessOf(rows),
                BlockApprovalAt.valueOf(rows.getString("block_approval_at")), rows.getBoolean("verdict_labels"), StartReview.valueOf(rows.getString("start_review")));
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
        if ("23514".equals(failure.getSQLState())) {
            return new IllegalArgumentException("Review with: choose an LLM provider and its model together, a harness, or"
                    + " neither; the saved combination is not allowed");
        }
        return database(failure);
    }

    private IllegalStateException database(SQLException failure) {
        return new IllegalStateException("Repository registry operation failed", failure);
    }
}
