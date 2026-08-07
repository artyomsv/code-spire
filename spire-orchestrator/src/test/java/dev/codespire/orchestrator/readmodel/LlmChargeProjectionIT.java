package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.TokenType;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeKind;
import dev.codespire.orchestrator.llm.ChargeLine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two properties of the ledger writer, both of which fail silently if broken: recording is idempotent
 * under redelivery, and an unpriced line is EXCLUDED from the known total rather than added as zero.
 * A zero-summing total looks complete and is not.
 *
 * <p>Each test gets its OWN review id (distinct from the {@code #1} literal
 * {@code LlmChargeSchemaIT} shares across the same table): {@code costOf}/{@code chargeLines}
 * aggregate by review_id with no per-test cleanup, so two tests sharing one id would sum each
 * other's charges.
 */
@QuarkusTest
class LlmChargeProjectionIT {

    @Inject
    ReviewProjection projection;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper mapper;

    private static String reviewId(long pr) {
        return "review::TEST-WS/TEST-REPO#" + pr;
    }

    private ChargeCall call(String reviewId, String ref, List<ChargeLine> lines) {
        return new ChargeCall(reviewId, ref, ChargeKind.REVIEW, "TEST-MODEL", lines);
    }

    @Test
    void recordingTheSameCallTwiceChargesItOnce() {
        String reviewId = reviewId(9101L);
        ChargeCall once = call(reviewId, "CANARY-REF-1",
                List.of(ChargeLine.metered(TokenType.INPUT, 1_000_000, 200_000L)));

        projection.recordCharges(once);
        projection.recordCharges(once);

        assertEquals(200_000L, projection.costOf(reviewId).knownCostMillicents());
        assertEquals(1, projection.chargeLines(reviewId).size());
    }

    @Test
    void anUnpricedLineIsCountedAsUnpricedNotAsZeroCost() {
        String reviewId = reviewId(9102L);
        projection.recordCharges(call(reviewId, "CANARY-REF-2", List.of(
                ChargeLine.metered(TokenType.INPUT, 1_000_000, 200_000L),
                ChargeLine.unknown(TokenType.CACHE_WRITE, 500_000))));

        ReviewProjection.CostSummary cost = projection.costOf(reviewId);
        assertEquals(200_000L, cost.knownCostMillicents());
        assertEquals(1, cost.unpricedCalls());
    }

    @Test
    void anUnmeteredCallContributesAnExplicitZeroAndIsNotFlaggedUnpriced() {
        String reviewId = reviewId(9103L);
        projection.recordCharges(call(reviewId, "CANARY-REF-3",
                List.of(ChargeLine.unmetered(TokenType.INPUT, 1_000_000))));

        ReviewProjection.CostSummary cost = projection.costOf(reviewId);
        assertEquals(0L, cost.knownCostMillicents());
        assertEquals(0, cost.unpricedCalls());
    }

    /**
     * The detail payload must carry the unpriced-call count, because that count is the ONLY thing that
     * qualifies the total the detail page computes from {@code chargeLines}.
     *
     * <p>A review whose every line is {@code UNKNOWN} sums to zero — the lines contribute nothing by
     * design — so without this figure the page renders a confident total that reads as "an operator
     * asserted this is free", which is exactly the conflation the ledger exists to remove. The UI
     * cannot infer it from a payload the server never sends: the field was absent from the record, so
     * it arrived as {@code undefined} and the qualifier was unreachable in production.
     */
    @Test
    void theDetailPayloadCarriesTheUnpricedCallCountThatQualifiesItsTotal() {
        long pr = 9105L;
        String reviewId = reviewId(pr);
        projection.registerHeader(reviewId, new RepoRef("TEST-WS", "TEST-REPO"), pr,
                "t", "a", "aid", "src", "dst", "TESTSHA9105", "http://example.invalid/pr", "github",
                "completed", 0);
        projection.recordCharges(call(reviewId, "CANARY-REF-5",
                List.of(ChargeLine.unknown(TokenType.INPUT, 1_000_000))));

        ReviewDetail detail = projection.loadDetail("TEST-WS", "TEST-REPO", pr).orElseThrow();

        // Paired assertion: the lines themselves sum to nothing, so the count is the whole difference
        // between "free" and "unknown" on this payload.
        assertEquals(1, detail.chargeLines().size());
        assertNull(detail.chargeLines().get(0).costMillicents(),
                "an unpriced line carries no cost — a 0 here would be an asserted zero");
        assertEquals(1, detail.unpricedCalls());
        // Asserted on the SERIALIZED payload, not only the typed accessor: what broke was the field
        // being absent from the wire, where the client read it as undefined and the comparison that
        // renders the qualifier silently became false. A typed getter cannot express that failure.
        assertEquals(1, unpricedCallsOnTheWire(detail),
                "the page cannot mark its total partial unless the payload says how many calls it omits");
    }

    private int unpricedCallsOnTheWire(ReviewDetail detail) {
        com.fasterxml.jackson.databind.JsonNode node = mapper.valueToTree(detail);
        List<String> fields = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        assertTrue(fields.contains("unpricedCalls"),
                "the detail payload carries no unpricedCalls field — it sends " + fields);
        return node.get("unpricedCalls").asInt();
    }

    /**
     * {@code chargeLines} is the UI's raw material for grouping lines back into calls, and it must
     * group by {@code callRef} — each line is written in its own transaction (one {@code update} per
     * line, no surrounding {@code @Transactional}), so two lines of the SAME call cannot be assumed to
     * share one {@code pricedAt}. This asserts the field this view exists to carry actually round-trips.
     */
    @Test
    void chargeLinesCarryTheCallRefTheyWereRecordedUnder() {
        String reviewId = reviewId(9104L);
        projection.recordCharges(call(reviewId, "CANARY-REF-4", List.of(
                ChargeLine.metered(TokenType.INPUT, 1_000_000, 200_000L),
                ChargeLine.metered(TokenType.OUTPUT, 500_000, 100_000L))));

        List<ReviewDetail.ChargeLineView> lines = projection.chargeLines(reviewId);
        assertEquals(2, lines.size());
        assertEquals("CANARY-REF-4", lines.get(0).callRef());
        assertEquals("CANARY-REF-4", lines.get(1).callRef());
    }
}
