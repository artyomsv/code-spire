package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeKind;
import dev.codespire.orchestrator.llm.ChargeLine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
