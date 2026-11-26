package dev.codespire.worker.pipeline;

import dev.codespire.contract.review.ReviewResult;
import dev.codespire.llm.ReviewPromptBuilder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What was cut from a review's two calls, carried onto its result (review input limit design, 2026-11-26), so a partial
 * review is marked on the dashboard, names the files the model did not fully see in the posted summary, and is never
 * posted as an approval (the orchestrator holds a clipped review's verdict, PR 456).
 */
final class ReviewClipping {

    private ReviewClipping() {
    }

    /** The parsed result, marked partial and naming the omitted files when the review or its reconcile was clipped. */
    static ReviewResult apply(ReviewResult parsed, ReviewPromptBuilder.Built review,
                              ReconcileCall.ReconcileOutcome reconcile) {
        boolean reconcileClipped = reconcile != null && reconcile.truncated();
        if (!review.truncated() && !reconcileClipped) {
            return parsed;
        }
        Set<String> omitted = new LinkedHashSet<>(review.omittedFiles());
        if (reconcile != null) {
            omitted.addAll(reconcile.omittedFiles());
        }
        return parsed.withTruncated(true).withOmittedFiles(List.copyOf(omitted));
    }
}
