package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.readmodel.ReviewProjection;

import java.util.Set;

/**
 * The links a review gathers context from (harness reviews, step 8). A fork author writes the title, branch and
 * description the links are read from, so they would choose which private issues and documents are loaded: an untrusted
 * pull request (a fork, or unknown provenance) gathers none, on the API and the harness path alike. Nothing is read
 * when there is no link to cut.
 */
final class ContextLinks {

    private ContextLinks() {
    }

    static Set<String> toGather(DiffFetched event, FixTargets targets, ReviewProjection projection) {
        Set<String> links = event.references() == null ? Set.of() : event.references();
        if (links.isEmpty() || !targets.isUntrustedReview(event.reviewId())) {
            return links;
        }
        projection.appendEvent(event.reviewId(), "result", "ContextLinksSkipped",
                "context links skipped: untrusted pull request");
        return Set.of();
    }
}
