package dev.codespire.orchestrator.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.contract.llm.ModelParamProfile.OutputTokenParam;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.llm.catalog.LlmModelCatalog;
import dev.codespire.orchestrator.llm.catalog.ModelSource;
import dev.codespire.orchestrator.llm.catalog.ResolvedModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The LLM model catalog (ADR-018): CRUD over {@code llm_model} plus its per-token-type rates. Pricing
 * itself (costing a call's token usage) is {@link LlmModelPricer}; validation of a save's pricing is
 * {@link LlmModelPricingValidator}. No secrets, so no encryption.
 */
@ApplicationScoped
public class LlmModelRegistry {

    private static final Logger LOG = Logger.getLogger(LlmModelRegistry.class);

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper mapper;

    @Inject
    LlmModelRateRepository rateRepository;

    @Inject
    LlmModelPricer pricer;

    @Inject
    LlmModelCatalog catalog;

    /**
     * The effective list: every manual model, plus every catalog model no manual model shadows, one row
     * per name, by type then name. Pickers and the build-setup guard read this and never see a name twice.
     */
    public List<LlmModelView> list() {
        List<LlmModelView> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM llm_model ORDER BY type, name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(toView(c, rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list LLM models", e);
        }
        out.addAll(CatalogModelViews.unshadowed(catalog.snapshot(), out));
        out.sort(Comparator.comparing(LlmModelView::type).thenComparing(LlmModelView::name));
        return out;
    }

    public Optional<LlmModelView> get(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM llm_model WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toView(c, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load LLM model " + id, e);
        }
    }

    @Transactional
    public LlmModelView create(LlmModelInput in) {
        LlmModelPricingValidator.Validated validated = LlmModelPricingValidator.validate(in);
        PricingMode mode = validated.mode();
        Map<TokenType, ModelRate> rates = validated.rates();
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO llm_model (id, type, name, label, pricing_mode, output_token_param,
                             supports_temperature, reasoning_effort, extra_params, enabled)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setObject(1, id);
            ps.setString(2, in.type());
            ps.setString(3, in.name());
            ps.setString(4, in.label());
            ps.setString(5, mode.name());
            ps.setString(6, normTokenParam(in.outputTokenParam()));
            ps.setBoolean(7, in.supportsTemperature() == null || in.supportsTemperature());
            ps.setString(8, blankToNull(in.reasoningEffort()));
            ps.setString(9, writeExtra(in.extraParams()));
            ps.setBoolean(10, in.enabled() == null || in.enabled());
            ps.executeUpdate();
            rateRepository.replaceRates(c, id, rates);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create LLM model", e);
        }
        return get(id).orElseThrow();
    }

    @Transactional
    public Optional<LlmModelView> update(UUID id, LlmModelInput in) {
        LlmModelPricingValidator.Validated validated = LlmModelPricingValidator.validate(in);
        PricingMode mode = validated.mode();
        Map<TokenType, ModelRate> rates = validated.rates();
        try (Connection c = dataSource.getConnection()) {
            ManualRow existing = manualRow(c, id);
            if (existing == null) {
                return Optional.empty();
            }
            requireChangeIsSafe(c, existing, in);
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE llm_model SET type=?, name=?, label=?, pricing_mode=?, output_token_param=?,
                            supports_temperature=?, reasoning_effort=?, extra_params=?, enabled=?,
                            updated_at=now() WHERE id=?
                    """)) {
                ps.setString(1, in.type());
                ps.setString(2, in.name());
                ps.setString(3, in.label());
                ps.setString(4, mode.name());
                ps.setString(5, normTokenParam(in.outputTokenParam()));
                ps.setBoolean(6, in.supportsTemperature() == null || in.supportsTemperature());
                ps.setString(7, blankToNull(in.reasoningEffort()));
                ps.setString(8, writeExtra(in.extraParams()));
                ps.setBoolean(9, in.enabled() == null || in.enabled());
                ps.setObject(10, id);
                ps.executeUpdate();
            }
            rateRepository.replaceRates(c, id, rates);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update LLM model " + id, e);
        }
        return get(id);
    }

    @Transactional
    public boolean delete(UUID id) {
        try (Connection c = dataSource.getConnection()) {
            ManualRow existing = manualRow(c, id);
            if (existing == null) {
                return false;
            }
            // Without this, deleting a chosen model leaves the default or a repository pointing at nothing, and every
            // review on it unpriceable. "Use catalog": a manual model that shadows an enabled, priced catalog entry of
            // the same vendor may go while in use, because its name still resolves (to that entry) after the delete.
            // Not serialised against a concurrent setting or repository save: the review path (the pricing refusal)
            // and the attention rows are the backstop for that race (spec section 6).
            if (!CatalogModelViews.stillResolvesAfterDelete(catalog.snapshot(), existing.name(), existing.type())) {
                requireUnused(c, existing.name(), ". Choose another model there first.");
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM llm_model WHERE id = ?")) {
                ps.setObject(1, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete LLM model " + id, e);
        }
    }

    // ---- pricing (delegated) -------------------------------------------------

    /** @see LlmModelPricer#priceCall(String, ModelUsage) */
    public List<ChargeLine> priceCall(String model, ModelUsage usage) {
        return pricer.priceCall(model, usage);
    }

    /**
     * Whether an operator has switched this model off. Deliberately NOT part of pricing: a finished run
     * must still be CHARGEABLE after its model is switched off, so {@code priceCall} keeps ignoring this
     * and only the guards that decide whether a run may START consult it. (The charge uses the rates the
     * catalogue holds when the charge is computed, not a quote frozen at dispatch.)
     */
    public boolean isDisabled(String model) {
        if (model == null || model.isBlank()) return false;
        try {
            // A model nobody has is NOT "disabled": it has no rates either, and the pricing guard already
            // refuses it with the types it cannot price. Answering true here would replace that precise
            // refusal with a switched-off message for a typo. A RETIRED catalog model is disabled.
            return catalog.resolve(model).map(resolved -> !resolved.enabled()).orElse(false);
        } catch (LlmModelCatalog.CatalogueReadFailed failure) {
            // NOT false. Answering "not switched off" on a failed read is failing open: pricing ignores
            // the enabled column on purpose, so a fully priced model an operator had switched off would
            // start a run on any database hiccup. The caller refuses with a reason that says what happened.
            LOG.warnf(failure, "Could not read whether model %s is enabled", model);
            throw new CatalogueUnavailable();
        }
    }

    /**
     * Whether this name resolves (manual first, then the catalog, by model id or alias) to an enabled model.
     * A read fault is {@link CatalogueUnavailable}, never an answer.
     */
    public boolean isKnownAndEnabled(String model) {
        if (model == null || model.isBlank()) return false;
        try {
            return catalog.resolve(model).map(ResolvedModel::enabled).orElse(false);
        } catch (LlmModelCatalog.CatalogueReadFailed failure) {
            LOG.warnf(failure, "Could not read whether model %s is known", model);
            throw new CatalogueUnavailable();
        }
    }

    /** The catalogue could not be read, so whether this model may run is unknown — never assumed. */
    public static final class CatalogueUnavailable extends RuntimeException {
        CatalogueUnavailable() { super("catalogue_unavailable"); }
    }

    /** @see LlmModelPricer#isPriceable(String) */
    public boolean isPriceable(String model) {
        return pricer.isPriceable(model);
    }

    /**
     * The API parameter profile for a model by wire name, for brokering to the
     * worker. Empty when the model is not catalogued — the caller falls back to
     * the legacy Chat Completions dialect.
     */
    public Optional<ModelParamProfile> profileForName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return catalog.resolve(name).map(ResolvedModel::profile);
        } catch (LlmModelCatalog.CatalogueReadFailed e) {
            LOG.warnf(e, "profile lookup failed for model %s", name);
            return Optional.empty();
        }
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * A chosen model is referenced by name ({@link ModelUses}), so renaming it orphans the choice exactly as deleting it
     * would, and changing its type makes the choice a model of another vendor than its provider. Every other field
     * stays freely editable while the model is in use. Not serialised against a concurrent setting or repository save;
     * the review path and the attention rows are the backstop (spec section 6).
     */
    private void requireChangeIsSafe(Connection c, ManualRow existing, LlmModelInput in) throws SQLException {
        if (!existing.name().equals(in.name())) {
            requireUnused(c, existing.name(), ". Choose another model there first, then rename it.");
        } else if (!existing.type().equals(in.type())) {
            requireUnused(c, existing.name(), ". Choose another model there first, then change its type.");
        }
    }

    private void requireUnused(Connection c, String name, String fix) throws SQLException {
        List<String> uses = ModelUses.of(c, catalog.snapshot(), name);
        if (!uses.isEmpty()) {
            throw new ModelInUseException("Model '" + name + "' is in use by " + String.join(", ", uses) + fix);
        }
    }

    /** The name and type a manual model is stored with; null when there is no such row. */
    private record ManualRow(String name, String type) {
    }

    private static ManualRow manualRow(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name, type FROM llm_model WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new ManualRow(rs.getString("name"), rs.getString("type")) : null;
            }
        }
    }

    private LlmModelView toView(Connection c, ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Map<String, Long> rates = new LinkedHashMap<>();
        List<String> notBilled = new java.util.ArrayList<>();
        rateRepository.ratesFor(c, id).forEach((type, rate) -> {
            if (rate.billed()) rates.put(type.name(), rate.millicentsPerMillion()); else notBilled.add(type.name());
        });
        return new LlmModelView(
                id.toString(),
                rs.getString("type"), rs.getString("name"), rs.getString("label"),
                rs.getString("pricing_mode"), rates,
                parseTokenParam(rs.getString("output_token_param")).name(),
                rs.getBoolean("supports_temperature"),
                rs.getString("reasoning_effort"),
                readExtra(rs.getString("extra_params")),
                rs.getBoolean("enabled"), rs.getTimestamp("created_at").toInstant(), List.copyOf(notBilled),
                ModelSource.MANUAL.name());
    }

    /** Normalize an operator-supplied token-param name to a valid enum name (default MAX_TOKENS). */
    private static String normTokenParam(String raw) {
        return parseTokenParam(raw).name();
    }

    /** A stored token-param name as the enum; MAX_TOKENS when blank or unknown. */
    public static OutputTokenParam parseTokenParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return OutputTokenParam.MAX_TOKENS;
        }
        try {
            return OutputTokenParam.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return OutputTokenParam.MAX_TOKENS;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String writeExtra(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(extra);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("extraParams must be a serializable JSON object", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readExtra(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            LOG.warnf("Corrupt extra_params, ignoring: %s", e.getMessage());
            return Map.of();
        }
    }
}
