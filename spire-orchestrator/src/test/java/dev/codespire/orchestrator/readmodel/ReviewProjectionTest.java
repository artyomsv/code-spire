package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.RepoRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reviews read model against a real Postgres (DevServices): register →
 * progress → complete, then verify the list + detail projections and the
 * derived pipeline stages.
 */
@QuarkusTest
class ReviewProjectionTest {

    @Inject
    ReviewProjection projection;

    private static final RepoRef REPO = new RepoRef("acme", "web");

    @Test
    void registersAndListsAReview() {
        long pr = 4101L;
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "Add checkout", "mtorres", "acc-1",
                "feature", "main", "cafe123", "https://x/pr/4101", "reviewing", ReviewProjection.STAGE_DIFF);
        projection.appendEvent(id, "integration", "PullRequestEventReceived", "opened");

        ReviewSummary found = projection.listSummaries().stream()
                .filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        assertEquals("acme", found.workspace());
        assertEquals("web", found.slug());
        assertEquals(pr, found.pr());
        assertEquals("reviewing", found.status());
        assertEquals("mtorres", found.author());
    }

    @Test
    void detailCarriesStagesFindingsAndEvents() {
        long pr = 4102L;
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "Refactor", "jlee", "acc-2",
                "fix", "main", "beef456", "https://x/pr/4102", "reviewing", ReviewProjection.STAGE_DIFF);
        projection.appendEvent(id, "integration", "PullRequestEventReceived", "opened");

        var result = new ReviewResult(
                List.of(new Finding("src/App.ts", new LineRange(42, 42), Severity.BLOCKER, "NPE risk", null)),
                "One blocker.", new ModelUsage("gpt-x", 1200, 90, 4100));
        projection.recordOutcome(id, result, ReviewProjection.STAGE_COMMENTS);
        projection.updateStatus(id, "completed", ReviewProjection.STAGE_DONE);

        ReviewDetail d = projection.loadDetail("acme", "web", pr).orElseThrow();
        assertEquals("completed", d.status());
        assertEquals(1, d.findings());
        assertEquals(6, d.stages().size());
        assertTrue(d.stages().stream().allMatch("done"::equals), "completed review = all steps done");
        assertEquals(1, d.findingsList().size());
        assertEquals("critical", d.findingsList().get(0).sev(), "BLOCKER maps to critical");
        assertEquals("src/App.ts:42", d.findingsList().get(0).loc());
        assertNotNull(d.usage());
        assertEquals("gpt-x", d.usage().model());
        assertTrue(d.events().stream().anyMatch(e -> e.type().equals("PullRequestEventReceived")));
    }

    @Test
    void missingReviewIsEmpty() {
        assertTrue(projection.loadDetail("acme", "web", 999999L).isEmpty());
    }
}
