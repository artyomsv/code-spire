package dev.codespire.orchestrator.llm;

import dev.codespire.orchestrator.attention.AttentionBroadcaster;
import dev.codespire.orchestrator.llm.catalog.LlmModelCatalog;
import dev.codespire.orchestrator.llm.catalog.ResolvedModel;
import dev.codespire.orchestrator.settings.AppSettingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * The global default review model: the model a review spends when its repository names no provider, beside the
 * default provider (docs/superpowers/specs/2026-11-20-llm-provider-without-model-design.md, section 4). Stored as the
 * app setting {@value #KEY}.
 *
 * <p>The default provider and the default model change together, in one transaction, so no review sees a provider of
 * one vendor with a model of another. "A default provider exists" means {@code is_default AND enabled}, as
 * {@link LlmProviderRegistry#resolveDefault()} reads it.
 */
@ApplicationScoped
public class LlmDefaultModel {

    public static final String KEY = "llm.default-model";

    static final String UNKNOWN_MODEL = "unknown_model";
    static final String MODEL_NOT_FOR_PROVIDER = "model_not_for_provider";
    static final String MODEL_NOT_PRICEABLE = "model_not_priceable";

    @Inject
    AppSettingRepository settings;

    @Inject
    LlmProviderRegistry registry;

    @Inject
    LlmModelRegistry models;

    @Inject
    LlmModelCatalog catalog;

    @Inject
    AttentionBroadcaster attention;

    @Inject
    DataSource dataSource;

    /**
     * The default review model, or empty when none is chosen.
     *
     * @throws AppSettingRepository.SettingReadFailed on a database fault, so the review is redelivered rather than
     *         refused {@code NO_DEFAULT_MODEL} for a hiccup
     */
    public Optional<String> current() {
        return settings.require(KEY);
    }

    /**
     * Change the default model for the current default provider; {@code null} clears it.
     *
     * @throws NoDefaultProvider when no enabled provider is the default: a model with no provider to spend it on
     * @throws ChoiceRefused when the model is unknown, of another vendor, or cannot be priced
     */
    @Transactional
    public void set(String model) {
        LlmProviderView provider = registry.enabledDefault().orElseThrow(NoDefaultProvider::new);
        if (model == null) {
            settings.delete(KEY);
        } else {
            requireChoice(provider.type(), model);
            settings.set(KEY, model);
        }
        attention.refresh();
    }

    /**
     * Make a provider the default together with its model, in one transaction: both are written, or neither.
     *
     * @return the provider, or empty when it does not exist (nothing is written)
     * @throws ChoiceRefused when the model is unknown, of another vendor, or cannot be priced
     */
    @Transactional
    public Optional<LlmProviderView> setDefaultProvider(UUID providerId, String model) {
        Optional<LlmProviderView> provider = registry.get(providerId);
        if (provider.isEmpty()) {
            return Optional.empty();
        }
        requireChoice(provider.orElseThrow().type(), model);
        try (Connection c = dataSource.getConnection()) {
            registry.setDefault(c, providerId);
            write(c, model);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set the default LLM provider " + providerId, e);
        }
        attention.refresh();
        return registry.get(providerId);
    }

    /** Write the default model on the caller's connection, inside the caller's transaction. */
    static void write(Connection c, String model) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO app_setting (key, value, updated_at) VALUES (?, ?, now())
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                """)) {
            ps.setString(1, KEY);
            ps.setString(2, model);
            ps.executeUpdate();
        }
    }

    /**
     * Why a model may not be chosen for a provider of this type, or null when it may. The model's type is the
     * resolver's (the catalog entry's provider, or the manual model's type).
     */
    public String check(String providerType, String model) {
        return refusal(providerType, model).map(ChoiceRefused::reason).orElse(null);
    }

    /** @throws ChoiceRefused naming the reason and the two types */
    public void requireChoice(String providerType, String model) {
        Optional<ChoiceRefused> refused = refusal(providerType, model);
        if (refused.isPresent()) {
            throw refused.orElseThrow();
        }
    }

    private Optional<ChoiceRefused> refusal(String providerType, String model) {
        Optional<ResolvedModel> resolved = model == null || model.isBlank() ? Optional.empty() : catalog.resolve(model);
        if (resolved.isEmpty()) {
            return Optional.of(new ChoiceRefused(UNKNOWN_MODEL, model, null, providerType));
        }
        String modelType = resolved.orElseThrow().type();
        if (!modelType.equals(providerType)) {
            return Optional.of(new ChoiceRefused(MODEL_NOT_FOR_PROVIDER, model, modelType, providerType));
        }
        if (!models.isPriceable(model)) {
            return Optional.of(new ChoiceRefused(MODEL_NOT_PRICEABLE, model, modelType, providerType));
        }
        return Optional.empty();
    }

    /** A model that may not be chosen for this provider; the message names the reason and the two types. */
    public static final class ChoiceRefused extends RuntimeException {
        private final String reason;

        ChoiceRefused(String reason, String model, String modelType, String providerType) {
            super(reason + ": model '" + model + "' is "
                    + (modelType == null ? "not in the catalog" : "a " + modelType + " model")
                    + "; the provider is " + providerType + ". Choose a priced " + providerType
                    + " model listed in Settings → LLM → Models.");
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** No enabled provider is the default, so there is nothing to choose a default model for. */
    public static final class NoDefaultProvider extends RuntimeException {
        public NoDefaultProvider() {
            super("No default LLM provider is set. Make a provider the default first, with its model.");
        }
    }
}
