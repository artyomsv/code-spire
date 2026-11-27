package dev.codespire.worker.pipeline;

import dev.codespire.contract.review.ReviewResult;
import dev.codespire.llm.ReviewPromptBuilder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What was cut from a review's two calls, carried onto its result (review input limit design, 2026-11-26), so a partial
 * review is marked on the dashboard, says in the posted summary what the model did not see, and is never posted as an
 * approval when the diff was cut (the orchestrator holds it, PR 456; a cut context alone does not, context cut design
 * 2026-11-27).
 */
final class ReviewClipping {

    private ReviewClipping() {
    }

    /** The parsed result, marked partial and naming what was cut when the review or its reconcile was clipped. */
    static ReviewResult apply(ReviewResult parsed, ReviewPromptBuilder.Built review,
                              ReconcileCall.ReconcileOutcome reconcile) {
        boolean reconcileClipped = reconcile != null && reconcile.truncated();
        if (!review.truncated() && !reconcileClipped) {
            return parsed;
        }
        Set<String> omitted = new LinkedHashSet<>(review.omittedFiles());
        Set<String> clipped = new LinkedHashSet<>(review.clippedInputs());
        if (reconcileClipped) {
            omitted.addAll(reconcile.omittedFiles());
            clipped.addAll(reconcileInputs(reconcile));
        }
        return parsed.withTruncated(true).withOmittedFiles(List.copyOf(omitted))
                .withClippedInputs(List.copyOf(clipped));
    }

    /**
     * What the reconcile cut. A blob replayed from before the inputs were named says only that something was cut, and
     * its omitted files say whether that was the diff; with none, the cut is read as the diff, the worst case.
     */
    private static List<String> reconcileInputs(ReconcileCall.ReconcileOutcome reconcile) {
        if (!reconcile.clippedInputs().isEmpty()) {
            return reconcile.clippedInputs();
        }
        return List.of(ReviewResult.DIFF_INPUT);
    }
}
