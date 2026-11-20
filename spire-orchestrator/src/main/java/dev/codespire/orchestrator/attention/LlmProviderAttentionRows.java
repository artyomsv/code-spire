package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.attention.AttentionView.Severity;
import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The attention rows about the LLM provider and the review model: no enabled provider, no default provider, no default
 * model, a chosen model that cannot be priced, a repository's provider switched off, and (through
 * {@link LlmChoiceAttentionRows}) a model of another vendor or switched off. Moved out of {@link AttentionQueries},
 * which calls it on its own connection.
 */
@ApplicationScoped
public class LlmProviderAttentionRows {

    /** What an API review needs priced (LlmModelPricer.isPriceable). */
    private static final List<TokenType> REQUIRED_RATES = List.of(TokenType.INPUT, TokenType.OUTPUT);

    @Inject
    LlmModelPricer pricer;

    /** Where a missing price is fixed for a catalog model. */
    @Inject
    LlmCatalogAttention catalogAttention;

    /** A chosen model of another vendor than its provider, or switched off: warnings, never refusals. */
    @Inject
    LlmChoiceAttentionRows choiceRows;

    void defaultRows(Connection c, List<AttentionView> rows) throws SQLException {
        if (count(c, "SELECT COUNT(*) FROM llm_provider WHERE enabled = TRUE") == 0) {
            rows.add(new AttentionView("LLM_PROVIDER_MISSING", Severity.BLOCKING, null,
                    "No enabled LLM provider is configured, so no review can run.", "/settings/llm"));
            return; // "no default" is not the actionable problem when there is nothing to default to
        }
        // Mirrors the real gate in LlmProviderRegistry.resolveDefault(): a default that has been
        // disabled resolves to nothing, so it is exactly as blocking as never setting one.
        if (count(c, "SELECT COUNT(*) FROM llm_provider WHERE enabled = TRUE AND is_default = TRUE") == 0) {
            rows.add(new AttentionView("LLM_DEFAULT_MISSING", Severity.BLOCKING, null,
                    "No enabled LLM provider is marked as the default, so no review can run.",
                    "/settings/llm"));
            return; // no default resolves to no model, so there is nothing to price-check
        }
        DefaultChoice choice = defaultChoice(c);
        if (choice.model() == null) {
            rows.add(new AttentionView("LLM_DEFAULT_MODEL_MISSING", Severity.BLOCKING, null,
                    "No default review model is selected, so every review on the default provider is refused."
                            + " Choose one in Settings → LLM.", "/settings/llm"));
            return;
        }
        modelPricingRow(choice.model(), rows);
        choiceRows.check(LlmChoiceAttentionRows.Choice.ofDefault(choice.providerType(), choice.model()), rows);
    }

    /**
     * The default provider names a model the ledger cannot price, so no review can start at all.
     *
     * <p>Existence of a provider and of a default is not enough: {@code ResultSaga}'s pre-spend guard
     * refuses to emit {@code GenerateReview} for an unpriceable model, and a refused review sits in
     * {@code REVIEWING} until {@code REVIEW_STUCK} eventually fires — a symptom one level away from
     * the cause, minutes later, on a panel that exists so blocking configuration announces itself
     * BEFORE work fails. The V30 upgrade makes this the guaranteed state of any model that carried a
     * legacy zero price, so it is the first thing an upgraded deployment hits.
     *
     * <p>Priceability comes from {@link LlmModelPricer#isPriceable} rather than a query written here:
     * three guards already share that one rule and a fourth definition of it could disagree with the
     * gate that actually blocks the review. It opens its own connection, which is why the model name is
     * all that is read from {@code c} — do not "optimise" that by threading this connection into the
     * pricer, whose callers are otherwise connectionless.
     */
    private void modelPricingRow(String model, List<AttentionView> rows) {
        if (model == null || pricer.isPriceable(model)) {
            return;
        }
        rows.add(new AttentionView("MODEL_NOT_PRICEABLE", Severity.BLOCKING,
                // A provider saved with no model at all is equally blocking, but naming "" as the
                // subject would render an empty label; the message alone carries it.
                model.isBlank() ? null : model,
                catalogAttention.catalogText(model, REQUIRED_RATES).orElse(
                        "The default review model has no usable pricing, so every review stops before"
                        + " it calls the model. Set input and output rates in Settings → LLM → Models,"
                        + " or mark the model unmetered if it is self-hosted."), "/settings/llm"));
    }

    /**
     * A repository whose named review provider cannot be spent, so every review and reply on it is refused
     * ({@code WorkerLlmCredentials.resolveForReview} never falls back to the default). The repository's row,
     * because each repository chooses its own provider.
     *
     * <p>{@code LLM_DEFAULT_MISSING} still fires when no default exists even if every repository names its
     * own provider. Accepted: a repository registered later, or one switched back to the default, would
     * have nothing to use.
     */
    void repositoryRows(Connection c, List<AttentionView> rows) throws SQLException {
        for (Named repository : namedChoices(c)) {
            repositoryRow(repository, rows);
        }
    }

    /** An enabled repository that names its own provider, with that provider's name, type and state, and its model. */
    private record Named(String repository, String provider, String type, String model, boolean enabled) {
    }

    private static List<Named> namedChoices(Connection c) throws SQLException {
        List<Named> named = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT r.workspace || '/' || r.slug AS repository, p.name, p.type, r.review_llm_model, p.enabled
                  FROM repository r JOIN llm_provider p ON p.id = r.review_llm_provider_id
                 WHERE r.enabled = TRUE
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) named.add(new Named(rs.getString("repository"), rs.getString("name"), rs.getString("type"),
                    blankIfNull(rs.getString("review_llm_model")), rs.getBoolean("enabled")));
        }
        return named;
    }

    /** At most one row per repository: its provider off, else its model unpriced, else the choice's own warnings. */
    private void repositoryRow(Named repository, List<AttentionView> rows) {
        if (!repository.enabled()) {
            rows.add(new AttentionView("REPOSITORY_LLM_PROVIDER_OFF", Severity.BLOCKING, repository.repository(),
                    "This repository reviews with LLM provider " + repository.provider() + ", which is switched"
                            + " off, so its reviews and replies are refused. Switch it on in Settings → LLM, or"
                            + " choose another provider in the repository's settings.", "/settings/repositories"));
        } else if (!pricer.isPriceable(repository.model())) {
            rows.add(new AttentionView("REPOSITORY_LLM_MODEL_NOT_PRICEABLE", Severity.BLOCKING,
                    repository.repository(), catalogAttention.catalogText(repository.model(), REQUIRED_RATES).orElse(
                            "This repository reviews with LLM provider " + repository.provider()
                            + " and model " + repository.model() + ", which has no usable pricing, so its reviews and"
                            + " replies are refused. Set input and output rates in Settings → LLM → Models, or"
                            + " mark the model unmetered if it is self-hosted."), "/settings/llm"));
        } else {
            choiceRows.check(LlmChoiceAttentionRows.Choice.ofRepository(repository.repository(), repository.type(),
                    repository.model()), rows);
        }
    }

    /** The enabled default provider's type and the default review model (null when none is chosen). */
    private record DefaultChoice(String providerType, String model) {
    }

    /** Read only once an enabled default exists (the caller checked), so the provider row is there. */
    private static DefaultChoice defaultChoice(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT p.type, s.value FROM llm_provider p
                  LEFT JOIN app_setting s ON s.key = 'llm.default-model'
                 WHERE p.enabled = TRUE AND p.is_default = TRUE
                """);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new DefaultChoice(rs.getString(1), rs.getString(2)) : new DefaultChoice(null, null);
        }
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private static int count(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
