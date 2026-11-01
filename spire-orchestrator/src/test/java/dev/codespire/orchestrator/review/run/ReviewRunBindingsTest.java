package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A review artifact is accepted only when it answers the call its row stores. */
class ReviewRunBindingsTest {

    private static final String RUN_ID = "run::github:TEST-WS/TEST-REPO:review-7-aaaaaaaaaaaa-0123abcd:1";

    private static final ReviewRunBinding STORED =
            new ReviewRunBinding("review::TEST-WS/TEST-REPO#7", ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot");

    private ReviewRunBinding stored = STORED;

    private ReviewRunBindings guard() {
        ReviewRunBindings guard = new ReviewRunBindings();
        guard.runs = new FactoryRunProjection() {
            @Override
            public Optional<ReviewRunBinding> reviewBindingOf(String runId) {
                return Optional.ofNullable(stored);
            }

            @Override
            protected void push(String runId) {
                throw new AssertionError("a guard broadcasts nothing");
            }
        };
        return guard;
    }

    private static RunResult artifact(ReviewRunBinding binding) {
        return new RunReviewArtifact(RUN_ID, binding, "TEST-findings", false, Map.of("INPUT", 1L), 1, List.of());
    }

    @Test
    void anArtifactWhoseBindingIsTheRowsIsAccepted() {
        assertTrue(guard().acceptsBinding(artifact(STORED)));
    }

    @Test
    void anArtifactWhoseBindingIsNotTheRowsIsRefused() {
        ReviewRunBinding otherHead = new ReviewRunBinding(STORED.reviewId(), STORED.kind(), 7, "b".repeat(40), "TEST-slot");
        ReviewRunBinding otherSlot = new ReviewRunBinding(STORED.reviewId(), STORED.kind(), 7, "a".repeat(40), "TEST-other");

        assertFalse(guard().acceptsBinding(artifact(otherHead)));
        assertFalse(guard().acceptsBinding(artifact(otherSlot)));
    }

    @Test
    void anArtifactForARunWithNoReviewRowIsRefused() {
        stored = null;

        assertFalse(guard().acceptsBinding(artifact(STORED)));
    }

    /** Only the artifact carries a binding; every other result is the projection's to decline. */
    @Test
    void aResultWithoutABindingIsAccepted() {
        stored = null;

        assertTrue(guard().acceptsBinding(new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "TEST-detail", false, null, List.of())));
    }
}
