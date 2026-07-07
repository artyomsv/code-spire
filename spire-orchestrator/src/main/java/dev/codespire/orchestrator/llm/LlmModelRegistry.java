package dev.codespire.orchestrator.llm;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The LLM model catalog (ADR-018): CRUD over {@code llm_model} plus the pricing
 * lookup used to cost a review's token usage. No secrets, so no encryption.
 */
@ApplicationScoped
public class LlmModelRegistry {

    private static final Logger LOG = Logger.getLogger(LlmModelRegistry.class);

    @Inject
    DataSource dataSource;

    public List<LlmModelView> list() {
        List<LlmModelView> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM llm_model ORDER BY type, name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(toView(rs));
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
                return rs.next() ? Optional.of(toView(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load LLM model " + id, e);
        }
    }

    @Transactional
    public LlmModelView create(LlmModelInput in) {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO llm_model (id, type, name, label, input_price_millicents_per_million,
                             output_price_millicents_per_million, enabled)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setObject(1, id);
            ps.setString(2, in.type());
            ps.setString(3, in.name());
            ps.setString(4, in.label());
            ps.setLong(5, in.inputPriceMillicentsPerMillion() == null ? 0L : in.inputPriceMillicentsPerMillion());
            ps.setLong(6, in.outputPriceMillicentsPerMillion() == null ? 0L : in.outputPriceMillicentsPerMillion());
            ps.setBoolean(7, in.enabled() == null || in.enabled());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create LLM model", e);
        }
        return get(id).orElseThrow();
    }

    @Transactional
    public Optional<LlmModelView> update(UUID id, LlmModelInput in) {
        try (Connection c = dataSource.getConnection()) {
            if (!exists(c, id)) {
                return Optional.empty();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE llm_model SET type=?, name=?, label=?, input_price_millicents_per_million=?,
                            output_price_millicents_per_million=?, enabled=?, updated_at=now() WHERE id=?
                    """)) {
                ps.setString(1, in.type());
                ps.setString(2, in.name());
                ps.setString(3, in.label());
                ps.setLong(4, in.inputPriceMillicentsPerMillion() == null ? 0L : in.inputPriceMillicentsPerMillion());
                ps.setLong(5, in.outputPriceMillicentsPerMillion() == null ? 0L : in.outputPriceMillicentsPerMillion());
                ps.setBoolean(6, in.enabled() == null || in.enabled());
                ps.setObject(7, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update LLM model " + id, e);
        }
        return get(id);
    }

    @Transactional
    public boolean delete(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM llm_model WHERE id = ?")) {
            ps.setObject(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete LLM model " + id, e);
        }
    }

    // ---- pricing (internal) ------------------------------------------------

    /** Price a review's token usage; 0 when the model is not in the catalog. */
    public long costMillicents(String model, int tokensIn, int tokensOut) {
        if (model == null || model.isBlank()) {
            return 0L;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT input_price_millicents_per_million, output_price_millicents_per_million "
                             + "FROM llm_model WHERE name = ?")) {
            ps.setString(1, model);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L; // uncatalogued model — no price, cost unknown
                }
                long in = rs.getLong(1);
                long out = rs.getLong(2);
                return ((long) tokensIn * in + (long) tokensOut * out) / 1_000_000L;
            }
        } catch (SQLException e) {
            LOG.warnf(e, "cost lookup failed for model %s", model);
            return 0L;
        }
    }

    // ---- helpers -----------------------------------------------------------

    private boolean exists(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM llm_model WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private LlmModelView toView(ResultSet rs) throws SQLException {
        return new LlmModelView(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("type"), rs.getString("name"), rs.getString("label"),
                rs.getLong("input_price_millicents_per_million"),
                rs.getLong("output_price_millicents_per_million"),
                rs.getBoolean("enabled"), rs.getTimestamp("created_at").toInstant());
    }
}
