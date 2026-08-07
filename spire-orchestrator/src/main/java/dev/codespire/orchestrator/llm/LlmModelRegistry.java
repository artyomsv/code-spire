package dev.codespire.orchestrator.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.contract.llm.ModelParamProfile.OutputTokenParam;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenType;
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

    public List<LlmModelView> list() {
        List<LlmModelView> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM llm_model ORDER BY type, name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(toView(c, rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list LLM models", e);
        }
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
        Map<TokenType, Long> rates = validated.rates();
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
        Map<TokenType, Long> rates = validated.rates();
        try (Connection c = dataSource.getConnection()) {
            String existingName = nameOf(c, id);
            if (existingName == null) {
                return Optional.empty();
            }
            requireRenameIsSafe(c, existingName, in.name());
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
            String name = nameOf(c, id);
            if (name == null) {
                return false;
            }
            // Without this, the save-time guard that a provider's model must be catalogued is
            // defeated after the fact: deleting the entry leaves the provider pointing at nothing
            // and every call it makes unpriceable.
            int users = countProvidersUsing(c, name);
            if (users > 0) {
                throw new ModelInUseException("Model '" + name + "' is in use by " + users
                        + " LLM provider(s). Point them at another model first.");
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
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT output_token_param, supports_temperature, reasoning_effort, extra_params "
                             + "FROM llm_model WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ModelParamProfile(
                        parseTokenParam(rs.getString("output_token_param")),
                        rs.getBoolean("supports_temperature"),
                        rs.getString("reasoning_effort"),
                        readExtra(rs.getString("extra_params"))));
            }
        } catch (SQLException e) {
            LOG.warnf(e, "profile lookup failed for model %s", name);
            return Optional.empty();
        }
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * {@code llm_provider.model} is a bare string reference, not a foreign key, so renaming a
     * catalogued model out from under a referencing provider would orphan it exactly as deleting the
     * model would — {@link #delete} already guards that; a rename needs the same guard. Only the
     * name is load-bearing here: every other field stays freely editable while the model is in use.
     */
    private void requireRenameIsSafe(Connection c, String existingName, String newName) throws SQLException {
        if (existingName.equals(newName)) {
            return;
        }
        int users = countProvidersUsing(c, existingName);
        if (users > 0) {
            throw new ModelInUseException("Model '" + existingName + "' is in use by " + users
                    + " LLM provider(s). Point them at another model first, then rename it.");
        }
    }

    private String nameOf(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM llm_model WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("name") : null;
            }
        }
    }

    private int countProvidersUsing(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM llm_provider WHERE model = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private LlmModelView toView(Connection c, ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Map<String, Long> rates = new LinkedHashMap<>();
        rateRepository.ratesFor(c, id).forEach((type, rate) -> rates.put(type.name(), rate));
        return new LlmModelView(
                id.toString(),
                rs.getString("type"), rs.getString("name"), rs.getString("label"),
                rs.getString("pricing_mode"), rates,
                parseTokenParam(rs.getString("output_token_param")).name(),
                rs.getBoolean("supports_temperature"),
                rs.getString("reasoning_effort"),
                readExtra(rs.getString("extra_params")),
                rs.getBoolean("enabled"), rs.getTimestamp("created_at").toInstant());
    }

    /** Normalize an operator-supplied token-param name to a valid enum name (default MAX_TOKENS). */
    private static String normTokenParam(String raw) {
        return parseTokenParam(raw).name();
    }

    private static OutputTokenParam parseTokenParam(String raw) {
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
