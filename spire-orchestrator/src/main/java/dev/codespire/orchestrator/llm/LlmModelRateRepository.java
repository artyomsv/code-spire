package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.TokenType;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code llm_model_rate} child table: one row per token type a catalog model bills for. Split out
 * of {@link LlmModelRegistry} — rate CRUD is a distinct concern from the model row itself, and folding
 * it into that class would have pushed it past the project's line limit.
 *
 * <p>Both methods take the caller's {@link Connection} rather than opening their own, so a create/update
 * that replaces rates does so in the same transaction as the {@code llm_model} row it belongs to.
 */
@ApplicationScoped
public class LlmModelRateRepository {

    /** Rates for one model, keyed by token type. Empty when the model has none (UNMETERED or unset). */
    public Map<TokenType, Long> ratesFor(Connection c, UUID modelId) throws SQLException {
        Map<TokenType, Long> rates = new EnumMap<>(TokenType.class);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT token_type, rate_millicents_per_million FROM llm_model_rate WHERE model_id = ?")) {
            ps.setObject(1, modelId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rates.put(TokenType.valueOf(rs.getString("token_type")),
                            rs.getLong("rate_millicents_per_million"));
                }
            }
        }
        return rates;
    }

    /** Replaces every rate row for a model with exactly the given set. */
    public void replaceRates(Connection c, UUID modelId, Map<TokenType, Long> rates) throws SQLException {
        deleteRates(c, modelId);
        if (rates == null || rates.isEmpty()) {
            return;
        }
        try (PreparedStatement ins = c.prepareStatement("""
                INSERT INTO llm_model_rate (model_id, token_type, rate_millicents_per_million)
                VALUES (?, ?, ?)
                """)) {
            for (Map.Entry<TokenType, Long> entry : rates.entrySet()) {
                ins.setObject(1, modelId);
                ins.setString(2, entry.getKey().name());
                ins.setLong(3, entry.getValue());
                ins.executeUpdate();
            }
        }
    }

    private void deleteRates(Connection c, UUID modelId) throws SQLException {
        try (PreparedStatement del = c.prepareStatement("DELETE FROM llm_model_rate WHERE model_id = ?")) {
            del.setObject(1, modelId);
            del.executeUpdate();
        }
    }
}
