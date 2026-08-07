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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * The per-token rate must not reach a viewer. A rate is operator-entered configuration, and
     * configuration reads are admin-only (ADR-022's third rule, which is why {@code LlmModelResource}
     * refuses a viewer) — while this payload is served under viewer+admin.
     *
     * <p>Asserted on the SERIALIZED payload and by field NAME, because that is the only place the
     * disclosure would happen: a record component added back for convenience would be silently sent,
     * and no typed assertion about cost or mode would notice.
     */
    @Test
    void theChargeLinePayloadDoesNotCarryThePerTokenRate() {
        long pr = 9107L;
        String reviewId = reviewId(pr);
        projection.registerHeader(reviewId, new RepoRef("TEST-WS", "TEST-REPO"), pr,
                "t", "a", "aid", "src", "dst", "TESTSHA9107", "http://example.invalid/pr", "github",
                "completed", 0);
        projection.recordCharges(call(reviewId, "CANARY-REF-7",
                List.of(ChargeLine.metered(TokenType.INPUT, 1_000_000, 200_000L))));

        ReviewDetail detail = projection.loadDetail("TEST-WS", "TEST-REPO", pr).orElseThrow();

        List<String> fields = new java.util.ArrayList<>();
        mapper.valueToTree(detail).get("chargeLines").get(0).fieldNames().forEachRemaining(fields::add);
        // Paired with a positive: a payload that carried no line fields at all would satisfy the
        // absence on its own, and prove nothing.
        assertTrue(fields.contains("costMillicents"), "the line still carries its own money: " + fields);
        assertFalse(fields.stream().anyMatch(f -> f.toLowerCase(java.util.Locale.ROOT).contains("rate")),
                "a viewer-visible payload must not carry a configured rate: " + fields);
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
     * Deleting a review deletes its ledger, because a re-registration of the same PR is the SAME
     * {@code review_id} — {@code ReviewIds.reviewId(repo, pr)} is stable per PR, not per run.
     *
     * <p>{@code deleteReview} already clears the worker's claims for exactly this reason, and its own
     * comment says why: delete-then-re-register is that same key. The ledger has the identical
     * property and {@code llm_charge.review_id} is plain TEXT with no FK, so nothing cascaded. Left
     * behind, the orphaned rows made the NEW review render the deleted run's money and model as its
     * own, kept the unpriced attention row raised for reviews that no longer exist, and collided with
     * the new run's own {@code call_ref} so its real charge was discarded too.
     */
    @Test
    void deletingAReviewDeletesItsChargesSoAReRegisteredPrCannotInheritThem() {
        long pr = 9106L;
        String reviewId = reviewId(pr);
        projection.registerHeader(reviewId, new RepoRef("TEST-WS", "TEST-REPO"), pr,
                "t", "a", "aid", "src", "dst", "TESTSHA9106", "http://example.invalid/pr", "github",
                "completed", 0);
        projection.recordCharges(call(reviewId, "CANARY-REF-6",
                List.of(ChargeLine.metered(TokenType.INPUT, 1_000_000, 200_000L))));

        assertTrue(projection.deleteReview("TEST-WS", "TEST-REPO", pr), "the review existed");

        assertTrue(projection.chargeLines(reviewId).isEmpty(), "the deleted review's lines are gone");
        ReviewProjection.CostSummary inherited = projection.costOf(reviewId);
        assertEquals(0L, inherited.knownCostMillicents(),
                "a re-registered PR would render the deleted run's spend as its own");
        assertNull(inherited.lastModel(), "and its model as the model it has not called yet");
    }

    /**
     * {@code chargeLines} is the UI's raw material for grouping lines back into calls, and it must group
     * by {@code callRef} — the ledger's own call identity. This asserts the field the view exists to
     * carry actually round-trips, and that a call's lines land together: they are written in one
     * transaction, so {@code priced_at DEFAULT now()} (the TRANSACTION timestamp) is the same on each.
     * A partial call is the failure that would otherwise be invisible — the missing lines do not exist,
     * so nothing flags the understated total they leave behind.
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
        assertEquals(lines.get(0).pricedAt(), lines.get(1).pricedAt(),
                "one call's lines are one transaction, so they carry one priced_at");
    }
}
