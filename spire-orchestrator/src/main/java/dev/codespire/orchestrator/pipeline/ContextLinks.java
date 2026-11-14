package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * The links a review gathers context from, on the API and the harness path alike (harness reviews, steps 8 and 8b).
 *
 * <p>A same-repository pull request gathers the links in its title, branch and description, as it always has. On an
 * untrusted one (a fork, or unknown provenance) the fork author writes that text, and could choose which private issues
 * and documents a public review loads; so it gathers only the links of sources whose author is trusted
 * ({@link LinkAuthorTrust}): the description when the pull request's author has push access, and comments by people
 * who have it. A message from a worker that sent no sources gathers none, as step 8 did. Nothing is read when there is
 * no link to decide about.
 */
@ApplicationScoped
public class ContextLinks {

    static final String SKIPPED_UNTRUSTED = "context links skipped: untrusted pull request";

    @Inject
    FixTargets targets;

    @Inject
    ReviewProjection projection;

    @Inject
    LinkAuthorTrust trust;

    Set<String> toGather(DiffFetched event) {
        Set<String> links = event.references() == null ? Set.of() : event.references();
        if (links.isEmpty() && event.linkSources().isEmpty()) {
            return links;
        }
        if (!targets.isUntrustedReview(event.reviewId())) {
            return links;
        }
        if (event.linkSources().isEmpty()) {
            projection.appendEvent(event.reviewId(), "result", "ContextLinksSkipped", SKIPPED_UNTRUSTED);
            return Set.of();
        }
        TrustedLinks kept = TrustedLinks.select(event.linkSources(),
                trust.decide(event.reviewId(), event.linkSources()));
        if (kept.links().isEmpty()) {
            projection.appendEvent(event.reviewId(), "result", "ContextLinksSkipped", kept.skippedDetail());
            return Set.of();
        }
        projection.appendEvent(event.reviewId(), "result", "ContextLinksTrusted", kept.keptDetail());
        return kept.links();
    }
}
