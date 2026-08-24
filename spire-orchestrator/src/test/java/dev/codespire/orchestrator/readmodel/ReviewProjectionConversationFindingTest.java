package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A finding raised from a conversation ({@code /finding}) joins the carry-forward baseline
 * ({@code open_findings_json}) rather than a fresh {@code findings_json} row — see
 * {@link ReviewProjection#addConversationFinding} for why.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ReviewProjectionConversationFindingTest {

    @Inject
    ReviewProjection projection;

    @Test
    void aConversationFindingJoinsTheCarryForwardBaseline() {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");

        PriorRun prior = projection.priorRunFor(reviewId).orElseThrow();
        assertTrue(prior.findings().stream()
                        .anyMatch(f -> "src/Foo.java".equals(f.path()) && f.line() == 44),
                "the carry-forward baseline must include the conversation finding");
    }

    @Test
    void aConversationFindingOnAnAlreadyFlaggedLineMergesRatherThanDoubling() {
        // dedupeByAnchor already enforces one anchor = one tracked concern. Nothing in the new code
        // fails if that stops working, which is exactly why it is asserted here.
        String reviewId = registerReviewWithOpenFindings("src/Foo.java:44", "warning");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "and it also shadows the field");

        List<ReviewDetail.FindingView> atAnchor = projection.openFindingsFor(reviewId).stream()
                .filter(f -> "src/Foo.java:44".equals(f.loc())).toList();
        assertEquals(1, atAnchor.size(), "one anchor must stay one tracked concern");
        assertTrue(atAnchor.getFirst().msg().contains("also shadows the field"));
    }

    @Test
    void aStoredRowWrittenBeforeOriginExistedReadsBackAsReviewDerived() {
        // open_findings_json rows already in the database have no origin field. Jackson leaves it
        // null, which must mean "the review reported this", not an unreadable row.
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");

        List<ReviewDetail.FindingView> open = projection.openFindingsFor(reviewId);

        assertFalse(open.isEmpty());
        assertNull(open.getFirst().origin());
    }

    @Test
    void aConversationFindingIsMarkedAsOne() {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");

        ReviewDetail.FindingView added = projection.openFindingsFor(reviewId).stream()
                .filter(f -> "src/Foo.java:44".equals(f.loc())).findFirst().orElseThrow();
        assertEquals("conversation", added.origin());
    }

    /**
     * Register a review with one open finding already tracked at {@code loc}, posted so
     * {@link ReviewProjection#priorRunFor} has a baseline to reconcile against — built on the
     * projection's own write API (register -> recordOutcome -> recordOpenFindings -> recordPosted),
     * the same sequence {@code ResultSaga} runs for a real round.
     */
    private String registerReviewWithOpenFindings(String loc, String sevSlug) {
        long pr = ReviewFixtures.newPr();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        String commit = "TESTSHA" + pr;
        projection.registerHeader(reviewId, ReviewFixtures.REPO_REF, pr, "TEST-TITLE", "TEST-AUTHOR",
                "TEST-AUTHOR-ID", "TEST-SOURCE", "TEST-TARGET", commit,
                "http://example.invalid/pr/" + pr, "github", "reviewing", 0);

        int splitAt = loc.lastIndexOf(':');
        String path = loc.substring(0, splitAt);
        int line = Integer.parseInt(loc.substring(splitAt + 1));
        ReviewResult result = new ReviewResult(
                List.of(new Finding(path, new LineRange(line, line), severityFor(sevSlug), "seed finding", null)),
                "TEST-SUMMARY", ModelUsage.of("TEST-MODEL", 1, 1));
        projection.recordOutcome(reviewId, result, ReviewProjection.STAGE_COMMENTS);
        projection.recordOpenFindings(reviewId, result, List.of(), List.of());
        projection.recordPosted(reviewId, commit, "TEST-SUM-" + pr);
        return reviewId;
    }

    private static Severity severityFor(String slug) {
        return switch (slug) {
            case "critical" -> Severity.BLOCKER;
            case "warning" -> Severity.MAJOR;
            case "suggestion" -> Severity.MINOR;
            default -> Severity.INFO;
        };
    }
}
