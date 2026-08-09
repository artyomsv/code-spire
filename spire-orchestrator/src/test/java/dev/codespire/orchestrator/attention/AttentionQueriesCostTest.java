package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An unpriced call is invisible in a money total by construction — it contributes nothing. Without a
 * row saying so, "$0.00" and "we could not price 40 calls" look identical on the dashboard.
 *
 * <p>These two are also the panel's only rows about the PAST, so they are the only ones that can be
 * acknowledged — the second half of this suite pins that the acknowledgement quiets the backlog it
 * counted without silencing the next call.
 */
@QuarkusTest
// Class-level, not per method: a security annotation that appears on only SOME methods of a class makes
// Quarkus reconfigure between them, which failed to boot when this class ran alongside another suite.
@TestSecurity(user = "test-viewer", roles = "spire-viewer")
class AttentionQueriesCostTest {

    @Inject
    AttentionQueries queries;

    @Inject
    DataSource dataSource;

    /**
     * Both the ledger AND the acknowledgement watermarks: the watermarks live in {@code app_setting}
     * and would otherwise outlive the test that wrote one, silencing every later test in this class.
     */
    @BeforeEach
    void reset() {
        sql("DELETE FROM llm_charge");
        sql("DELETE FROM app_setting WHERE key LIKE 'attention.llm-%'");
    }

    @Test
    void anUnpricedChargeRaisesAWarningPointingAtTheModelSettings() {
        insertUnpricedCharge("review::TEST-WS/TEST-REPO#1", "TEST-MODEL");

        List<AttentionView> rows = queries.collect();

        assertTrue(rows.stream().anyMatch(r -> "LLM_COST_UNPRICED".equals(r.code())
                && r.severity() == AttentionView.Severity.WARNING
                && "/settings/llm".equals(r.action())
                && "/api/attention/ack/LLM_COST_UNPRICED".equals(r.dismiss())));
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
                && "/api/attention/ack/LLM_USAGE_UNRECONCILED".equals(r.dismiss())));
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

    // ---- acknowledgement: the rate on a written line is immutable, so a fix cannot clear these ----

    /**
     * Entering the missing rates cannot repair rows already written — the rate is snapshotted onto each
     * line by design — so without this the row was permanent and monotonically growing from the first
     * unpriced call a deployment ever made, which V30 guarantees on every upgrade.
     */
    @Test
    void acknowledgingTheUnpricedRowQuietsTheBacklogItCounted() {
        insertUnpricedCharge("review::TEST-WS/TEST-REPO#6", "TEST-MODEL");
        recordedBeforeTheAck("CANARY-UNPRICED");

        queries.acknowledge(CostAttentionRow.UNPRICED);

        assertTrue(queries.collect().stream().noneMatch(r -> "LLM_COST_UNPRICED".equals(r.code())),
                "an acknowledged backlog must stop nagging, or the row can never clear");
    }

    /**
     * The half that makes a watermark better than a time window: the row comes BACK for a call recorded
     * after the acknowledgement, and counts only that call.
     */
    @Test
    void anUnpricedCallRecordedAfterTheAcknowledgementRaisesTheRowAgain() {
        insertUnpricedCharge("review::TEST-WS/TEST-REPO#7", "TEST-MODEL");
        recordedBeforeTheAck("CANARY-UNPRICED");
        queries.acknowledge(CostAttentionRow.UNPRICED);

        insertUnpriced("review::TEST-WS/TEST-REPO#8", "CANARY-UNPRICED-LATER");
        recordedAfterTheAck("CANARY-UNPRICED-LATER");

        AttentionView row = queries.collect().stream()
                .filter(r -> "LLM_COST_UNPRICED".equals(r.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("a new unpriced call must raise the row again"));
        assertTrue(row.message().startsWith("1 LLM call(s)"),
                "only the unacknowledged call is counted — got: " + row.message());
    }

    /**
     * Only these two conditions may be acknowledged. A code naming a state-derived row is a 404, not a
     * silent success — otherwise the endpoint would be a way to make a broken system look healthy.
     */
    @Test
    void theAckEndpointAcceptsOnlyTheTwoLedgerConditions() {
        given().when().post("/api/attention/ack/LLM_COST_UNPRICED").then().statusCode(204);
        given().when().post("/api/attention/ack/REVIEW_FAILED").then().statusCode(404);
        given().when().post("/api/attention/ack/LLM_DEFAULT_MISSING").then().statusCode(404);
    }

    /** The two conditions are acknowledged independently: they name different causes. */
    @Test
    void acknowledgingTheUnpricedRowLeavesTheUnreconciledOneRaised() {
        insertUnpricedCharge("review::TEST-WS/TEST-REPO#9", "TEST-MODEL");
        insertUnreconciledCharge("review::TEST-WS/TEST-REPO#10", "TEST-MODEL");
        recordedBeforeTheAck("CANARY-UNPRICED");
        recordedBeforeTheAck("CANARY-UNRECONCILED");

        queries.acknowledge(CostAttentionRow.UNPRICED);

        List<AttentionView> rows = queries.collect();
        assertTrue(rows.stream().noneMatch(r -> "LLM_COST_UNPRICED".equals(r.code())));
        assertTrue(rows.stream().anyMatch(r -> "LLM_USAGE_UNRECONCILED".equals(r.code())),
                "a mapping defect is a different cause and must not be acknowledged by proxy");
    }

    // ---- fixtures: mirror the ledger writer's own shapes (LlmModelPricer), not arbitrary rows -----

    /**
     * Move a charge either side of the acknowledgement explicitly, instead of relying on the app's
     * {@code Instant.now()} and the database's {@code now()} agreeing to the millisecond. What is under
     * test is the comparison against the watermark, not two clocks.
     */
    private void recordedBeforeTheAck(String callRef) {
        sql("UPDATE llm_charge SET priced_at = now() - interval '1 hour' WHERE call_ref = '" + callRef + "'");
    }

    private void recordedAfterTheAck(String callRef) {
        sql("UPDATE llm_charge SET priced_at = now() + interval '1 hour' WHERE call_ref = '" + callRef + "'");
    }

    /**
     * A reconciled call on an uncatalogued (or otherwise unpriceable) model: {@code LlmModelPricer}
     * answers {@code ChargeLine.unknown(type, tokens)} per token type, never {@code TOTAL}.
     */
    private void insertUnpricedCharge(String reviewId, String model) {
        insert(reviewId, "CANARY-UNPRICED", model, "UNKNOWN", "INPUT", "NULL", "NULL");
    }

    /** As above under a caller-chosen {@code call_ref}, for a test needing two distinct calls. */
    private void insertUnpriced(String reviewId, String callRef) {
        insert(reviewId, callRef, "TEST-MODEL", "UNKNOWN", "INPUT", "NULL", "NULL");
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
