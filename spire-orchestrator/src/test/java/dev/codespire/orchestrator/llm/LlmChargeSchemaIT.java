package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.TokenType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger's CHECK constraints are the backstop, so they are asserted at the SQL layer rather than
 * assumed from the service that writes them. Four of these are NEGATIVE assertions — "this must be
 * rejected" — and a negative assertion passes trivially if the constraint is simply absent, so each
 * one below is paired with the positive case that proves the insert path works at all.
 */
@QuarkusTest
class LlmChargeSchemaIT {

    @Inject
    DataSource dataSource;

    private void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private String insert(String mode, String tokenType, String rate, String cost) {
        return "INSERT INTO llm_charge (id, review_id, call_ref, kind, model, pricing_mode, "
                + "token_type, tokens, rate_millicents_per_million, cost_millicents) VALUES "
                + "(gen_random_uuid(), 'review::TEST-WS/TEST-REPO#1', 'CANARY-" + tokenType + mode
                + "', 'REVIEW', 'TEST-MODEL', '" + mode + "', '" + tokenType + "', 10, " + rate + ", " + cost + ")";
    }

    private String insertKind(String kind) {
        return "INSERT INTO llm_charge (id, review_id, call_ref, kind, model, pricing_mode, "
                + "token_type, tokens, rate_millicents_per_million, cost_millicents) VALUES "
                + "(gen_random_uuid(), 'review::TEST-WS/TEST-REPO#1', 'CANARY-KIND-" + kind
                + "', '" + kind + "', 'TEST-MODEL', 'UNKNOWN', 'INPUT', 10, NULL, NULL)";
    }

    @Test
    void aMeteredLineWithARateAndACostIsAccepted() {
        assertDoesNotThrow(() -> exec(insert("METERED", "INPUT", "250000", "2")));
    }

    @Test
    void aMeteredLineWithoutARateIsRejected() {
        assertThrows(SQLException.class, () -> exec(insert("METERED", "OUTPUT", "NULL", "2")));
    }

    @Test
    void anUnknownLineMustCarryNoCostAndNoRate() {
        assertDoesNotThrow(() -> exec(insert("UNKNOWN", "INPUT", "NULL", "NULL")));
        assertThrows(SQLException.class, () -> exec(insert("UNKNOWN", "OUTPUT", "NULL", "0")));
    }

    /** An asserted zero must be exactly zero on both columns — never a stray rate. */
    @Test
    void anUnmeteredLineMustBeZeroRateAndZeroCost() {
        assertDoesNotThrow(() -> exec(insert("UNMETERED", "INPUT", "0", "0")));
        assertThrows(SQLException.class, () -> exec(insert("UNMETERED", "OUTPUT", "250000", "0")));
    }

    /** An unreconciled call has no split, so no metered rate can apply to it. */
    @Test
    void aTotalLineCannotBeMetered() {
        assertThrows(SQLException.class, () -> exec(insert("METERED", "TOTAL", "250000", "2")));
        assertDoesNotThrow(() -> exec(insert("UNMETERED", "TOTAL", "0", "0")));
    }

    /** The redelivery guard: one call's dimension can be charged exactly once. */
    @Test
    void theSameCallAndTokenTypeCannotBeChargedTwice() throws SQLException {
        String sql = insert("METERED", "CACHE_WRITE", "300000", "3");
        exec(sql);
        assertThrows(SQLException.class, () -> exec(sql));
    }

    /**
     * A negative cost is not a small error: it SUBTRACTS from the review's total and from any
     * deployment-wide sum, so one rate typo would silently reduce reported spend — worse than an
     * unpriced call, which at least raises an attention row.
     *
     * <p>The refusal is asserted to name its CHECK. A bare {@code SQLException} is also what a UNIQUE
     * collision or a typo'd literal throws, so "it threw" would pass whether or not the constraint
     * exists — the failure mode this file's own header warns about.
     */
    @Test
    void aNegativeCostIsRejectedByItsOwnCheck() {
        assertDoesNotThrow(() -> exec(costing("CANARY-COST-POSITIVE", "2")));
        SQLException refused =
                assertThrows(SQLException.class, () -> exec(costing("CANARY-COST-NEGATIVE", "-2")));
        assertTrue(refused.getMessage().contains("llm_charge_cost_non_negative"),
                "rejected for some other reason, so this proves nothing: " + refused.getMessage());
    }

    /** A metered REASONING line at a fixed rate, with the cost as the only variable. */
    private String costing(String callRef, String cost) {
        return "INSERT INTO llm_charge (id, review_id, call_ref, kind, model, pricing_mode, "
                + "token_type, tokens, rate_millicents_per_million, cost_millicents) VALUES "
                + "(gen_random_uuid(), 'review::TEST-WS/TEST-REPO#1', '" + callRef + "', 'REVIEW', "
                + "'TEST-MODEL', 'METERED', 'REASONING', 10, 250000, " + cost + ")";
    }

    @Test
    void aTypoedTokenTypeIsRejected() {
        assertThrows(SQLException.class, () -> exec(insert("UNKNOWN", "INPUTT", "NULL", "NULL")));
    }

    @Test
    void aTypoedKindIsRejected() {
        String sql = "INSERT INTO llm_charge (id, review_id, call_ref, kind, model, pricing_mode, "
                + "token_type, tokens, rate_millicents_per_million, cost_millicents) VALUES "
                + "(gen_random_uuid(), 'review::TEST-WS/TEST-REPO#1', 'CANARY-BADKIND', 'reviewww', "
                + "'TEST-MODEL', 'UNKNOWN', 'INPUT', 10, NULL, NULL)";
        assertThrows(SQLException.class, () -> exec(sql));
    }

    /**
     * The ledger's token_type CHECK must accept every token type the code can produce. Without this,
     * adding a new type without amending the migration turns a new billing dimension into a runtime
     * insert failure — the charge is lost at exactly the moment it first occurs.
     */
    @Test
    void theTokenTypeCheckAcceptsEveryKnownTokenType() {
        for (TokenType type : TokenType.values()) {
            // A distinct call_ref per iteration: other tests already charge (call_ref, token_type)
            // pairs like ('CANARY-INPUTUNKNOWN', 'INPUT'), and this loop must not collide with them.
            String sql = "INSERT INTO llm_charge (id, review_id, call_ref, kind, model, pricing_mode, "
                    + "token_type, tokens, rate_millicents_per_million, cost_millicents) VALUES "
                    + "(gen_random_uuid(), 'review::TEST-WS/TEST-REPO#1', 'CANARY-ENUM-" + type.name()
                    + "', 'REVIEW', 'TEST-MODEL', 'UNKNOWN', '" + type.name() + "', 10, NULL, NULL)";
            assertDoesNotThrow(() -> exec(sql),
                    "llm_charge.token_type CHECK rejects TokenType." + type
                            + " — add it to the CHECK in V30__llm_charge_ledger.sql");
        }
    }

    /**
     * The ledger's kind CHECK must accept every ChargeKind the writer can produce. Without this, adding
     * a call kind without amending the migration turns that kind's first charge into a lost INSERT.
     */
    @Test
    void theKindCheckAcceptsEveryChargeKind() {
        for (ChargeKind kind : ChargeKind.values()) {
            assertDoesNotThrow(() -> exec(insertKind(kind.name())),
                    "llm_charge.kind CHECK rejects ChargeKind." + kind
                            + " — add it to the CHECK in V30__llm_charge_ledger.sql");
        }
    }

    @Test
    void theDroppedTablesAndColumnsAreGone() {
        assertThrows(SQLException.class, () -> exec("SELECT 1 FROM review_llm_call"));
        assertThrows(SQLException.class, () -> exec("SELECT cost_millicents FROM review_status"));
        assertThrows(SQLException.class,
                () -> exec("SELECT input_price_millicents_per_million FROM llm_model"));
    }
}
