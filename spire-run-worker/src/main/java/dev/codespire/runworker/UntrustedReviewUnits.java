package dev.codespire.runworker;

import dev.codespire.contract.review.PullRefs;
import dev.codespire.runtime.RunUnitSpec;

/**
 * The checks and the init variable that make a built review unit a fork's (harness reviews, step 8). The fork's code
 * runs in the agent, so the agent must never be beside a real model credential: the unit is refused unless its model
 * proxy holds it. The refusal tests the built unit, because the three ways {@code RunUnitBuilder} falls back to a
 * direct credential (a mode that does not proxy reviews, egress off, an arm with no route) all leave it without one.
 */
final class UntrustedReviewUnits {

    private UntrustedReviewUnits() {
    }

    /** Refuses a unit with no model proxy and gives init the pull ref its head is fetched from. */
    static RunUnitSpec sealed(RunUnitSpec unit, String pullRef) {
        // The arm with no route is only known once built.
        if (unit.modelProxy() == null) {
            throw new IllegalStateException(RunUnitBuilder.FORK_PROXY_REQUIRED);
        }
        // Defence in depth: the command's own constructor already refuses an invalid pull ref.
        if (!PullRefs.isValid(pullRef)) {
            throw new IllegalArgumentException("A fork review fetches a pull ref");
        }
        return unit.withInit(RunUnitBuilder.withVariable(unit.init(), RunUnitBuilder.FETCH_REF, pullRef));
    }
}
