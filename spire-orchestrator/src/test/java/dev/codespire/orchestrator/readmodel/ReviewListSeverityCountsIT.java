package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.RepoRef;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reviews list's findings badges: the open findings per severity, read from the stored row with no migration.
 * Carried-over findings count under their own severity, and a resolved one counts nowhere.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ReviewListSeverityCountsIT {

    @Inject ReviewProjection projection;

    @Test
    void theListRowCountsOpenFindingsPerSeverityCarriedIncluded() {
        String reviewId = "review::ws/severity-counts-it#1";
        projection.registerHeader(reviewId, new RepoRef("ws", "severity-counts-it"), 1L,
                "t", "a", "aid", "src", "dst", "c1", "http://x", "github", "completed", 6);
        projection.recordOutcome(reviewId, new ReviewResult(List.of(
                finding("src/A.java", Severity.BLOCKER), finding("src/B.java", Severity.MINOR),
                finding("src/C.java", Severity.NIT)), "sum", ModelUsage.of("m", 1, 1)), 6);
        projection.recordReconciliation(reviewId,
                List.of(verdict("t-open", FindingVerdict.Status.STILL_OPEN),
                        verdict("t-fixed", FindingVerdict.Status.RESOLVED)),
                List.of(new PriorFinding("src/Old.java", 3, Severity.MAJOR, "still open", "t-open"),
                        new PriorFinding("src/Old.java", 3, Severity.BLOCKER, "fixed", "t-fixed")));

        ReviewSummary row = row(reviewId);

        assertEquals(4, row.findings());
        assertEquals(1, row.carriedOverFindings());
        assertEquals(new SeverityCounts(1, 1, 1, 1), row.openBySeverity(),
                "the carried MAJOR counts as a warning; the resolved BLOCKER counts nowhere");
    }

    @Test
    void aReviewWithNoFindingsHasNoBadges() {
        String reviewId = "review::ws/severity-counts-it#2";
        projection.registerHeader(reviewId, new RepoRef("ws", "severity-counts-it"), 2L,
                "t", "a", "aid", "src", "dst", "c2", "http://x", "github", "completed", 6);

        assertEquals(new SeverityCounts(0, 0, 0, 0), row(reviewId).openBySeverity());
    }

    private ReviewSummary row(String reviewId) {
        return projection.listSummaries(false).stream()
                .filter(s -> s.id().equals(reviewId)).findFirst().orElseThrow();
    }

    private static Finding finding(String path, Severity severity) {
        return new Finding(path, new LineRange(9, 9), severity, "raised now", null);
    }

    private static FindingVerdict verdict(String threadRef, FindingVerdict.Status status) {
        return new FindingVerdict(threadRef, "src/Old.java", 3, status, "noted");
    }
}
