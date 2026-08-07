package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.ModelUsage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backstop for a rate the validator's upper bound would now refuse but an older row already holds:
 * pricing it must not fabricate money. {@code tokens × rate} is a {@code long} of millicents, so a rate
 * that large wraps to a NEGATIVE cost — a figure that subtracts from the review's total and from any
 * deployment-wide sum, and (unlike an unpriced call) raises no attention row to say so.
 *
 * <p>Deliberately reaches the state through the catalog with a rate the API can no longer accept, by
 * rewriting the stored rate afterwards. That is exactly the shape a legacy row has: written when nothing
 * bounded it. Cleaned up on the way out, so nothing is left for another suite to trip over.
 */
@QuarkusTest
class LlmModelPricerOverflowIT {

    /** 1e13 millicents/1M tokens; at a million tokens the product exceeds {@code Long.MAX_VALUE}. */
    private static final long OVERFLOWING_RATE = 10_000_000_000_000L;

    private static final String MODEL = "TEST-OVERFLOW-MODEL";

    @Inject
    LlmModelPricer pricer;

    @Inject
    LlmModelRegistry registry;

    @Inject
    DataSource dataSource;

    @Test
    void aRateThatOverflowsIsPricedAsUnknownNeverAsNegativeMoney() {
        LlmModelView model = registry.create(new LlmModelInput("openai", MODEL, "TEST overflow",
                "METERED", Map.of("INPUT", 250_000L, "OUTPUT", 400_000L),
                "MAX_TOKENS", true, null, Map.of(), true));
        try {
            rewriteInputRate(model.id(), OVERFLOWING_RATE);

            List<ChargeLine> lines = pricer.priceCall(MODEL, ModelUsage.of(MODEL, 1_000_000, 500_000));

            assertFalse(lines.isEmpty(), "a call that happened must stay countable");
            assertTrue(lines.stream().noneMatch(l -> l.costMillicents() != null && l.costMillicents() < 0),
                    "a negative cost subtracts from every total it lands in: " + lines);
            assertTrue(lines.stream().anyMatch(l -> l.mode() == PricingMode.UNKNOWN),
                    "a cost that cannot be computed is UNKNOWN, which raises the attention row: " + lines);
        } finally {
            registry.delete(UUID.fromString(model.id()));
        }
    }

    /** What no API path can write any more: a rate above the validator's bound. */
    private void rewriteInputRate(String modelId, long rate) {
        String sql = "UPDATE llm_model_rate SET rate_millicents_per_million = " + rate
                + " WHERE model_id = '" + UUID.fromString(modelId) + "' AND token_type = 'INPUT'";
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("could not plant the legacy rate", e);
        }
    }
}
