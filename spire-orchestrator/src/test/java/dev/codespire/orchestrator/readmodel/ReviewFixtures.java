package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.review.TokenType;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeKind;
import dev.codespire.orchestrator.llm.ChargeLine;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared review fixtures. Written once here because several archival tasks need them; every value is
 * obviously-synthetic (TEST- prefixes, round token counts) so a fixture can never be mistaken for a
 * real review or a real vendor price.
 *
 * <p>Built on the projection's own write API — {@code registerHeader}, {@code updateStatus},
 * {@code recordCharges} — rather than raw SQL, so a fixture cannot drift into a row shape the
 * production writer would never produce.
 */
public final class ReviewFixtures {

    public static final String WS = "TEST-WS";
    public static final String REPO = "TEST-REPO";
    public static final RepoRef REPO_REF = new RepoRef(WS, REPO);

    /** Obviously not a real vendor price: 200 000 millicents per million tokens is $2.00/1M flat. */
    private static final long TEST_RATE_MILLICENTS_PER_MILLION = 200_000L;
    private static final String TEST_MODEL = "TEST-MODEL";

    // Well clear of every hand-written pr literal in this module's other suites, which live in the
    // low thousands.
    private static final AtomicLong NEXT_PR = new AtomicLong(9_000_000L);

    private ReviewFixtures() {
    }

    /** A PR number no other test uses — the module shares one database across test classes. */
    public static long newPr() {
        return NEXT_PR.incrementAndGet();
    }

    public static String reviewIdFor(long pr) {
        return ReviewIds.reviewId(REPO_REF, pr);
    }

    /** A finished review that really spent money — the baseline for every retention assertion. */
    public static void seedCompletedReviewWithCharges(ReviewProjection projection, long pr) {
        String reviewId = seedHeader(projection, pr, "completed", ReviewProjection.STAGE_DONE);
        // Two lines, so a cost assertion sums something rather than echoing one row back.
        projection.recordCharges(ChargeCall.forReview(reviewId, "CANARY-REF-" + pr, ChargeKind.REVIEW, TEST_MODEL,
                List.of(ChargeLine.metered(TokenType.INPUT, 1_000_000, TEST_RATE_MILLICENTS_PER_MILLION),
                        ChargeLine.metered(TokenType.OUTPUT, 500_000, TEST_RATE_MILLICENTS_PER_MILLION))));
    }

    /**
     * The same review with an empty ledger — what a PR re-registered after a purge looks like, so a
     * cost read on it reports only what a purged predecessor leaked into it.
     */
    public static void seedCompletedReviewWithoutCharges(ReviewProjection projection, long pr) {
        seedHeader(projection, pr, "completed", ReviewProjection.STAGE_DONE);
    }

    /** A run still in flight: the one state archiving must refuse. */
    public static void seedReviewingReview(ReviewProjection projection, long pr) {
        seedHeader(projection, pr, "reviewing", ReviewProjection.STAGE_REVIEW);
    }

    /** A terminal failure — the state that raises a REVIEW_FAILED attention row. */
    public static void seedFailedReview(ReviewProjection projection, long pr) {
        seedHeader(projection, pr, "failed", ReviewProjection.STAGE_REVIEW);
    }

    /**
     * Register the header, then move it to the wanted outcome. {@code registerHeader} always claims a
     * run, so the status argument only ever applies to a first INSERT — the follow-up
     * {@code updateStatus} is what makes the state deterministic when a test re-seeds an id.
     */
    private static String seedHeader(ReviewProjection projection, long pr, String status, int stage) {
        String reviewId = reviewIdFor(pr);
        projection.registerHeader(reviewId, REPO_REF, pr, "TEST-TITLE", "TEST-AUTHOR", "TEST-AUTHOR-ID",
                "TEST-SOURCE", "TEST-TARGET", "TESTSHA" + pr, "http://example.invalid/pr/" + pr,
                "github", status, stage);
        projection.updateStatus(reviewId, status, stage);
        return reviewId;
    }
}
