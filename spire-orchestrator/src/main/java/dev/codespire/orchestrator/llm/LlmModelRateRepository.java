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

    /**
     * What the catalog says about each token type of one model. Empty when it says nothing (UNMETERED,
     * or a model nobody has priced).
     *
     * <p>{@code getLong} answers 0 for SQL NULL, so a NOT_BILLED row read without {@code wasNull} would
     * arrive as a zero PRICE — the fabricated zero the whole assertion exists to avoid. The billing
     * column is read first and decides which shape the value takes.
     */
    public Map<TokenType, ModelRate> ratesFor(Connection c, UUID modelId) throws SQLException {
        Map<TokenType, ModelRate> rates = new EnumMap<>(TokenType.class);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT token_type, rate_millicents_per_million, billing FROM llm_model_rate WHERE model_id = ?")) {
            ps.setObject(1, modelId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TokenType type = TokenType.valueOf(rs.getString("token_type"));
                    if (!"RATED".equals(rs.getString("billing"))) {
                        rates.put(type, ModelRate.notBilled());
                        continue;
                    }
                    long rate = rs.getLong("rate_millicents_per_million");
                    if (rs.wasNull()) continue; // a RATED row with no rate is not a price; say nothing
                    rates.put(type, ModelRate.rated(rate));
                }
            }
        }
        return rates;
    }

    /** Replaces every rate row for a model with exactly the given set. */
    public void replaceRates(Connection c, UUID modelId, Map<TokenType, ModelRate> rates) throws SQLException {
        deleteRates(c, modelId);
        if (rates == null || rates.isEmpty()) {
            return;
        }
        try (PreparedStatement ins = c.prepareStatement("""
                INSERT INTO llm_model_rate (model_id, token_type, rate_millicents_per_million, billing)
                VALUES (?, ?, ?, ?)
                """)) {
            for (Map.Entry<TokenType, ModelRate> entry : rates.entrySet()) {
                ModelRate rate = entry.getValue();
                ins.setObject(1, modelId);
                ins.setString(2, entry.getKey().name());
                if (rate.billed()) ins.setLong(3, rate.millicentsPerMillion()); else ins.setNull(3, java.sql.Types.BIGINT);
                ins.setString(4, rate.billed() ? "RATED" : "NOT_BILLED");
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
