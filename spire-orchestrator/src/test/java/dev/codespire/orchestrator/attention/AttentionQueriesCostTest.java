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

    /**
     * A purge hard-deletes the review its charges belonged to, so there is no page left to send an
     * operator to and no rate anyone can now enter. Both rows therefore ignore purged charges.
     *
     * <p>Asserted for BOTH codes and paired with the raised state first: they are two separate queries,
     * so filtering only one is the easy mistake, and on a ledger that raised nothing to begin with the
     * negative half would pass with neither filter present.
     */
    @Test
    void aPurgedChargeRaisesNeitherCostRow() {
        insertUnpricedCharge("review::TEST-WS/TEST-REPO#11", "TEST-MODEL");
        insertUnreconciledCharge("review::TEST-WS/TEST-REPO#12", "TEST-MODEL");
        List<AttentionView> whileLive = queries.collect();
        assertTrue(whileLive.stream().anyMatch(r -> "LLM_COST_UNPRICED".equals(r.code())));
        assertTrue(whileLive.stream().anyMatch(r -> "LLM_USAGE_UNRECONCILED".equals(r.code())));

        // What the future purge stamps, in the transaction that deletes the review row.
        sql("UPDATE llm_charge SET archived_at = now()");

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

    /**
     * A run's unpriceable spend is a run's row, not a review's mapping defect.
     *
     * <p>The two review rows diagnose a review. Unscoped, a run reporting no usable token split fired
     * {@code LLM_USAGE_UNRECONCILED}, whose text names a {@code TokenUsageMapper} defect — a class in
     * another module, on the review path, that cannot be the cause — and which carries no action. The
     * absence assertion is what discriminates: the run row alone would still pass with the review
     * rows left unscoped.
     */
    @Test
    void aRunsUnpriceableSpendIsReportedAsARunRatherThanAsAReviewMappingDefect() {
        insertUnpricedRunCharge("run::github:TEST-acme/app:s:1", "TEST-RUN-MODEL");

        List<AttentionView> rows = queries.collect();

        assertTrue(rows.stream().anyMatch(r -> "RUN_SPEND_UNPRICED".equals(r.code())
                && r.severity() == AttentionView.Severity.WARNING
                && "/settings/llm".equals(r.action())
                && "/api/attention/ack/RUN_SPEND_UNPRICED".equals(r.dismiss())));
        assertTrue(rows.stream().noneMatch(r -> "LLM_USAGE_UNRECONCILED".equals(r.code())),
                "a run's row must not be reported as a defect in the review path's usage mapper");
        assertTrue(rows.stream().noneMatch(r -> "LLM_COST_UNPRICED".equals(r.code())));
    }

    /**
     * The routine case, and the reason the run row is keyed on pricing mode rather than on
     * reconciliation.
     *
     * <p>A harness that reports only a high-water total is behaving exactly as its adapter documents,
     * and on an unmetered model the cost is an asserted zero — nothing is missing from the cap, so
     * there is nothing to report. Unscoped, every such run lit a permanent, growing row about a
     * defect that did not exist, which is the wallpaper this panel exists to keep out.
     */
    @Test
    void anUnmeteredRunAssertsItsZeroAndRaisesNothing() {
        insertUnreconciledUnmeteredRunCharge("run::github:TEST-acme/app:s:2", "TEST-RUN-MODEL");

        List<AttentionView> rows = queries.collect();

        assertTrue(rows.stream().noneMatch(r -> "RUN_SPEND_UNPRICED".equals(r.code())));
        assertTrue(rows.stream().noneMatch(r -> "LLM_USAGE_UNRECONCILED".equals(r.code())));
        assertTrue(rows.stream().noneMatch(r -> "LLM_COST_UNPRICED".equals(r.code())));
    }

    /** A review's unpriceable call still reaches the review row, so the scoping cut only one way. */
    @Test
    void scopingTheReviewRowsToReviewsLeavesThemFiringForReviews() {
        insertUnreconciledCharge("review::TEST-WS/TEST-REPO#1", "TEST-MODEL");

        List<AttentionView> rows = queries.collect();

        assertTrue(rows.stream().anyMatch(r -> "LLM_USAGE_UNRECONCILED".equals(r.code())));
        assertTrue(rows.stream().noneMatch(r -> "RUN_SPEND_UNPRICED".equals(r.code())));
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

    /**
     * A factory run whose usage could not be priced: {@code RunTokenUsage} answers an unreconciled
     * {@code ModelUsage} for a harness that reports no usable split, and {@code LlmModelPricer} turns
     * that into one {@code ChargeLine.unknown(TOTAL, n)} — the shape the run path writes most often.
     */
    private void insertUnpricedRunCharge(String runId, String model) {
        insertRun(runId, "CANARY-RUN-UNPRICED", model, "UNKNOWN", "TOTAL", "NULL", "NULL");
    }

    /**
     * The same run on an UNMETERED model. {@code LlmModelPricer} consults the catalog BEFORE the
     * reconciled check, so an unbilled model records an asserted zero whatever the split turns out to
     * be — which is what makes marking a model unmetered the action that clears the run row.
     */
    private void insertUnreconciledUnmeteredRunCharge(String runId, String model) {
        insertRun(runId, "CANARY-RUN-UNMETERED", model, "UNMETERED", "TOTAL", "0", "0");
    }

    /** A reconciled call on an unmetered model: {@code ChargeLine.unmetered(type, tokens)} per type. */
    private void insertUnmeteredCharge(String reviewId, String model) {
        insert(reviewId, "CANARY-UNMETERED", model, "UNMETERED", "INPUT", "0", "0");
    }

    private void insert(String reviewId, String callRef, String model, String pricingMode,
                        String tokenType, String rate, String cost) {
        // subject_kind is left to its V42 default of REVIEW, which is what every row above is.
        sql("INSERT INTO llm_charge (id, subject_id, call_ref, kind, model, pricing_mode, "
                + "token_type, tokens, rate_millicents_per_million, cost_millicents) VALUES "
                + "(gen_random_uuid(), '" + reviewId + "', '" + callRef + "', 'REVIEW', '" + model
                + "', '" + pricingMode + "', '" + tokenType + "', 10, " + rate + ", " + cost + ")");
    }

    /** As {@link #insert}, naming the subject kind and charge kind a factory run actually writes. */
    private void insertRun(String runId, String callRef, String model, String pricingMode,
                           String tokenType, String rate, String cost) {
        sql("INSERT INTO llm_charge (id, subject_id, subject_kind, call_ref, kind, model, "
                + "pricing_mode, token_type, tokens, rate_millicents_per_million, cost_millicents) "
                + "VALUES (gen_random_uuid(), '" + runId + "', 'RUN', '" + callRef + "', 'BUILD', '"
                + model + "', '" + pricingMode + "', '" + tokenType + "', 10, " + rate + ", "
                + cost + ")");
    }

    private void sql(String statement) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + statement, e);
        }
    }
}
