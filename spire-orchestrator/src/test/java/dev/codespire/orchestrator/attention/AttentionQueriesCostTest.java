package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An unpriced call is invisible in a money total by construction — it contributes nothing. Without a
 * row saying so, "$0.00" and "we could not price 40 calls" look identical on the dashboard.
 */
@QuarkusTest
class AttentionQueriesCostTest {

    @Inject
    AttentionQueries queries;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void reset() {
        sql("DELETE FROM llm_charge");
    }

    @Test
    void anUnpricedChargeRaisesAWarningPointingAtTheModelSettings() {
        insertUnpricedCharge("review::TEST-WS/TEST-REPO#1", "TEST-MODEL");

        List<AttentionView> rows = queries.collect();

        assertTrue(rows.stream().anyMatch(r -> "LLM_COST_UNPRICED".equals(r.code())
                && r.severity() == AttentionView.Severity.WARNING
                && "/settings/llm".equals(r.action())
                && r.dismiss() == null));
        assertTrue(rows.stream().noneMatch(r -> "LLM_USAGE_UNRECONCILED".equals(r.code())));
    }

    /**
     * On a metered model this fixture is unpriced AND unreconciled at once (see the fixture's own
     * javadoc), which is exactly the double-report case the two codes must not both raise for. Only
     * the mapping-defect row should appear — pricing has nothing to fix here.
     */
    @Test
    void anUnreconciledCallRaisesOnlyItsOwnRowNotTheUnpricedOne() {
        insertUnreconciledCharge("review::TEST-WS/TEST-REPO#2", "TEST-MODEL");

        List<AttentionView> rows = queries.collect();

        assertTrue(rows.stream().anyMatch(r -> "LLM_USAGE_UNRECONCILED".equals(r.code())
                && r.action() == null
                && r.dismiss() == null));
        assertTrue(rows.stream().noneMatch(r -> "LLM_COST_UNPRICED".equals(r.code())));
    }

    @Test
    void aFullyPricedLedgerRaisesNeitherRow() {
        insertMeteredCharge("review::TEST-WS/TEST-REPO#3", "TEST-MODEL");

        List<AttentionView> rows = queries.collect();

        assertTrue(rows.stream().noneMatch(r -> "LLM_COST_UNPRICED".equals(r.code())));
        assertTrue(rows.stream().noneMatch(r -> "LLM_USAGE_UNRECONCILED".equals(r.code())));
    }

    /**
     * The case that justifies having two codes rather than one: an unmetered model's cost is an
     * ASSERTED zero, not an unknown, so an unreconciled call on it must raise only the mapping-defect
     * row and never the pricing row — unlike the metered fixture above, {@code pricing_mode} here is
     * genuinely not {@code UNKNOWN}.
     */
    @Test
    void anUnreconciledUnmeteredCallRaisesOnlyTheUnreconciledRow() {
        insertUnreconciledUnmeteredCharge("review::TEST-WS/TEST-REPO#4", "TEST-MODEL");

        List<AttentionView> rows = queries.collect();

        assertTrue(rows.stream().anyMatch(r -> "LLM_USAGE_UNRECONCILED".equals(r.code())));
        assertTrue(rows.stream().noneMatch(r -> "LLM_COST_UNPRICED".equals(r.code())));
    }

    /** A reconciled, priced call on an unmetered model raises neither row. */
    @Test
    void aReconciledUnmeteredChargeRaisesNeitherRow() {
        insertUnmeteredCharge("review::TEST-WS/TEST-REPO#5", "TEST-MODEL");

        List<AttentionView> rows = queries.collect();

        assertTrue(rows.stream().noneMatch(r -> "LLM_COST_UNPRICED".equals(r.code())));
        assertTrue(rows.stream().noneMatch(r -> "LLM_USAGE_UNRECONCILED".equals(r.code())));
    }

    // ---- fixtures: mirror the ledger writer's own shapes (LlmModelPricer), not arbitrary rows -----

    /**
     * A reconciled call on an uncatalogued (or otherwise unpriceable) model: {@code LlmModelPricer}
     * answers {@code ChargeLine.unknown(type, tokens)} per token type, never {@code TOTAL}.
     */
    private void insertUnpricedCharge(String reviewId, String model) {
        insert(reviewId, "CANARY-UNPRICED", model, "UNKNOWN", "INPUT", "NULL", "NULL");
    }

    /**
     * An unreconciled call on a metered model: {@code LlmModelPricer} has no per-type split to price,
     * so it answers {@code ChargeLine.unknown(TOTAL, reportedTotal)} — unpriced AND unreconciled at
     * once, which is the real shape of this failure, not two independent ones.
     */
    private void insertUnreconciledCharge(String reviewId, String model) {
        insert(reviewId, "CANARY-UNRECONCILED", model, "UNKNOWN", "TOTAL", "NULL", "NULL");
    }

    /** A reconciled call on a catalogued metered model, priced normally. */
    private void insertMeteredCharge(String reviewId, String model) {
        insert(reviewId, "CANARY-METERED", model, "METERED", "INPUT", "250000", "2");
    }

    /**
     * An unreconciled call on an UNMETERED model: {@code LlmModelPricer} still has no split to price,
     * but an unmetered model's cost is an asserted zero rather than unknown, so this is
     * {@code pricing_mode='UNMETERED'} with a zero rate/cost — unlike {@link #insertUnreconciledCharge},
     * it must NOT also read as unpriced.
     */
    private void insertUnreconciledUnmeteredCharge(String reviewId, String model) {
        insert(reviewId, "CANARY-UNRECONCILED-UNMETERED", model, "UNMETERED", "TOTAL", "0", "0");
    }

    /** A reconciled call on an unmetered model: {@code ChargeLine.unmetered(type, tokens)} per type. */
    private void insertUnmeteredCharge(String reviewId, String model) {
        insert(reviewId, "CANARY-UNMETERED", model, "UNMETERED", "INPUT", "0", "0");
    }

    private void insert(String reviewId, String callRef, String model, String pricingMode,
                        String tokenType, String rate, String cost) {
        sql("INSERT INTO llm_charge (id, review_id, call_ref, kind, model, pricing_mode, "
                + "token_type, tokens, rate_millicents_per_million, cost_millicents) VALUES "
                + "(gen_random_uuid(), '" + reviewId + "', '" + callRef + "', 'REVIEW', '" + model
                + "', '" + pricingMode + "', '" + tokenType + "', 10, " + rate + ", " + cost + ")");
    }

    private void sql(String statement) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + statement, e);
        }
    }
}
