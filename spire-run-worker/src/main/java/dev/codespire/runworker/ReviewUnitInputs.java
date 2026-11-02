package dev.codespire.runworker;

import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.runtime.RunUnitSpec;

/**
 * A review unit's inputs volume (harness reviews, step 6): init mounts it writable, the agent read-only at
 * {@code /inputs}, and the publisher not at all.
 */
final class ReviewUnitInputs {

    private ReviewUnitInputs() {
    }

    static RunUnitSpec withInputs(RunUnitSpec unit, HarnessReviewInputs inputs) {
        return unit;
    }
}
