package dev.codespire.orchestrator.factory;

import dev.codespire.orchestrator.llm.ChargeKind;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
