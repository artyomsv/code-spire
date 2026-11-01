package dev.codespire.orchestrator.factory;

import dev.codespire.orchestrator.llm.ChargeKind;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A review run kind is charged under the call it answers, and only the three review kinds are reviews. */
class RunKindTest {

    @Test
    void everyReviewKindChargesUnderItsOwnName() {
        assertEquals(ChargeKind.REVIEW, RunKind.REVIEW.chargeKind());
        assertEquals(ChargeKind.RECONCILE, RunKind.RECONCILE.chargeKind());
        assertEquals(ChargeKind.FOLLOWUP, RunKind.FOLLOWUP.chargeKind());
    }

    @Test
    void onlyTheReviewKindsAreReviews() {
        Set<RunKind> reviews = EnumSet.noneOf(RunKind.class);
        for (RunKind kind : RunKind.values()) {
            if (kind.isReview()) {
                reviews.add(kind);
            }
        }

        assertEquals(EnumSet.of(RunKind.REVIEW, RunKind.RECONCILE, RunKind.FOLLOWUP), reviews);
    }

    /** A review row is written only through the call it answers, which V107 refuses it without. */
    @Test
    void aReviewRunRowIsRecordedOnlyThroughItsCall() {
        FactoryRunProjection.QueuedRun row = new FactoryRunProjection.QueuedRun("run::github:TEST-WS/TEST-REPO:TEST-s:1",
                "TEST-harness", "TEST-MODEL", "main", "TESTSHA0", "spire/TEST-s", "TEST-bot", null);
        dev.codespire.contract.review.ReviewRunBinding call = new dev.codespire.contract.review.ReviewRunBinding(
                "review::TEST-WS/TEST-REPO#7", dev.codespire.contract.review.ReviewRunBinding.Kind.RECONCILE, 7,
                "a".repeat(40), "TEST-slot", 1);

        for (RunKind review : EnumSet.of(RunKind.REVIEW, RunKind.RECONCILE, RunKind.FOLLOWUP)) {
            assertThrows(IllegalArgumentException.class, () -> row.ofKind(review), review.name());
        }
        FactoryRunProjection.QueuedRun reviewRow = row.asReviewFor(call);
        assertEquals("RECONCILE", reviewRow.kind());
        assertEquals(call.reviewId(), reviewRow.reviewId());
        assertEquals(call.commit(), reviewRow.baseCommit(), "the row's base is the head the review reads");
        assertEquals(call, reviewRow.paidBySubscription().review(), "every wither keeps the call");
    }
}
