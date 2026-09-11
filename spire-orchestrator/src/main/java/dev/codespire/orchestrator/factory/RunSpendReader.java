package dev.codespire.orchestrator.factory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** The detail's token split, read on the projection's existing connection. */
final class RunSpendReader {

    private RunSpendReader() { }

    static RunSpend read(Connection connection, String runId) throws SQLException {
        String sql = """
                SELECT token_type, SUM(tokens) AS tokens, SUM(cost_millicents) AS cost,
                       COUNT(*) FILTER (WHERE cost_millicents IS NULL) AS unpriced
                  FROM llm_charge
                 WHERE subject_kind = 'RUN' AND subject_id = ? AND archived_at IS NULL
                 GROUP BY token_type
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (var result = statement.executeQuery()) {
                Map<String, Long> tokens = new LinkedHashMap<>();
                long priced = 0;
                int unpriced = 0;
                while (result.next()) {
                    tokens.put(result.getString("token_type"), result.getLong("tokens"));
                    // A NULL sum contributes zero to the priced SUBTOTAL only. The unpriced count
                    // below keeps this from becoming a known total, even for an all-unpriced run.
                    priced = Math.addExact(priced, result.getLong("cost"));
                    unpriced = Math.addExact(unpriced, result.getInt("unpriced"));
                }
                return new RunSpend(priced, unpriced, tokens);
            }
        }
    }
}
