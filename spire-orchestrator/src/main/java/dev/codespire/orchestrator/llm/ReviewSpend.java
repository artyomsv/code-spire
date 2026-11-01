package dev.codespire.orchestrator.llm;

import java.util.Objects;

/**
 * Who a harness review run's spend is recorded under: the review, the call slot's key, the review call's kind, and the
 * pool member that paid. The review counterpart of {@link RunSpend}.
 *
 * @param reviewId      the review this spend belongs to
 * @param callRef       the deterministic key — see {@link CallRefs#of}
 * @param kind          REVIEW, RECONCILE or FOLLOWUP
 * @param credentialRef which pool member paid, or null when the run names none
 */
public record ReviewSpend(String reviewId, String callRef, ChargeKind kind, String credentialRef) {

    public ReviewSpend {
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(callRef, "callRef");
        Objects.requireNonNull(kind, "kind");
        if (kind != ChargeKind.REVIEW && kind != ChargeKind.RECONCILE && kind != ChargeKind.FOLLOWUP) {
            throw new IllegalArgumentException("review " + reviewId + " cannot be charged as " + kind
                    + ", which is a run's kind; a review call is REVIEW, RECONCILE or FOLLOWUP");
        }
    }
}
