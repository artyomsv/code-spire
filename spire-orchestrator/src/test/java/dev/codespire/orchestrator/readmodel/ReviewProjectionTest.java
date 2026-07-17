package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Inject
    DataSource dataSource;

    @Inject
    ReviewThreadView threads;

    private static final RepoRef REPO = new RepoRef("acme", "web");

    @Test
    void registersAndListsAReview() {
        long pr = 4101L;
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "Add checkout", "mtorres", "acc-1",
                "feature", "main", "cafe123", "https://x/pr/4101", "github", "reviewing",
                ReviewProjection.STAGE_DIFF);
        projection.appendEvent(id, "integration", "PullRequestEventReceived", "opened");

        ReviewSummary found = projection.listSummaries().stream()
                .filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        assertEquals("acme", found.workspace());
        assertEquals("web", found.slug());
        assertEquals(pr, found.pr());
        assertEquals("reviewing", found.status());
        assertEquals("mtorres", found.author());
        assertEquals("acc-1", found.authorId(), "numeric provider id surfaces in the list summary (C9)");
        assertEquals("github", found.providerType(), "stored provider type surfaces in the list summary (C7)");
    }

    @Test
    void detailCarriesStagesFindingsAndEvents() {
        long pr = 4102L;
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "Refactor", "jlee", "acc-2",
                "fix", "main", "beef456", "https://x/pr/4102", "github", "reviewing",
                ReviewProjection.STAGE_DIFF);
        projection.appendEvent(id, "integration", "PullRequestEventReceived", "opened");

        var result = new ReviewResult(
                List.of(new Finding("src/App.ts", new LineRange(42, 42), Severity.BLOCKER, "NPE risk", null)),
                "One blocker.", new ModelUsage("gpt-x", 1200, 90, 4100));
        projection.recordOutcome(id, result, ReviewProjection.STAGE_COMMENTS);
        projection.updateStatus(id, "completed", ReviewProjection.STAGE_DONE);

        ReviewDetail d = projection.loadDetail("acme", "web", pr).orElseThrow();
        assertEquals("completed", d.status());
        assertEquals("acc-2", d.authorId(), "numeric provider id carried on the detail (C9)");
        assertEquals("github", d.providerType(), "stored provider type carried on the detail (C7)");
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
    void attemptCounterStartsAtOneAndBumpsOnRetry() {
        long pr = 4104L;
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "Retry me", "kdev", "acc-3",
                "feature", "main", "d00d", "https://x/pr/4104", "github", "reviewing",
                ReviewProjection.STAGE_REVIEW);

        assertEquals(1, projection.currentAttempt(id), "a freshly registered review is on attempt 1");

        projection.retryPipeline(id, 2, "retrying (attempt 2/3)");
        assertEquals(2, projection.currentAttempt(id), "retry bumps the attempt counter");

        ReviewDetail d = projection.loadDetail("acme", "web", pr).orElseThrow();
        assertEquals(2, d.attempt(), "attempt carried on the detail payload");
        assertEquals("reviewing", d.status(), "retry puts the review back into REVIEWING");
        assertEquals(ReviewProjection.STAGE_DIFF, d.stage(), "retry restarts at the diff step");

        // Re-registering (a manual re-push / new commit) resets the budget.
        projection.registerHeader(id, REPO, pr, "Retry me", "kdev", "acc-3",
                "feature", "main", "beef", "https://x/pr/4104", "github", "reviewing",
                ReviewProjection.STAGE_DIFF);
        assertEquals(1, projection.currentAttempt(id), "re-registration resets the attempt counter to 1");
    }

    @Test
    void concurrentAppendsNeverDuplicateSeq() throws Exception {
        long pr = 4105L;
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "Race me", "cdev", "acc-4",
                "feature", "main", "f00d", "https://x/pr/4105", "github", "reviewing",
                ReviewProjection.STAGE_DIFF);

        // Three consumers (IntegrationSaga, ResultSaga, DomainEventSink) append to
        // the same review concurrently — UNIQUE(review_id, seq) + retry (V6) must
        // keep the timeline gap-free and duplicate-free.
        int appends = 24;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < appends; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    projection.appendEvent(id, "result", "RaceEvent-" + n, "");
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) AS total, COUNT(DISTINCT seq) AS distinct_seq, MAX(seq) AS max_seq "
                             + "FROM review_event WHERE review_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(appends, rs.getInt("total"));
                assertEquals(appends, rs.getInt("distinct_seq"), "no duplicate seq under concurrency");
                assertEquals(appends, rs.getInt("max_seq"), "seq is gap-free 1..n");
            }
        }
    }

    @Test
    void missingReviewIsEmpty() {
        assertTrue(projection.loadDetail("acme", "web", 999999L).isEmpty());
    }

    @Test
    void deleteRemovesRowTimelineAndEventStream() throws Exception {
        long pr = 4106L;
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "Delete me", "ddev", "acc-5",
                "feature", "main", "dead", "https://x/pr/4106", "github", "reviewing",
                ReviewProjection.STAGE_DIFF);
        projection.appendEvent(id, "integration", "PullRequestEventReceived", "opened");
        // Seed the underlying event stream too, so we can assert it is cleared.
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO event_log (event_id, stream_id, sequence, event_type, event_version, "
                             + "payload, occurred_at) VALUES (?, ?, 1, 'X', 1, ?, now())")) {
            ps.setObject(1, java.util.UUID.randomUUID());
            ps.setString(2, id);
            ps.setBytes(3, new byte[]{1});
            ps.executeUpdate();
        }

        assertTrue(projection.deleteReview("acme", "web", pr), "deleting an existing review reports success");

        assertTrue(projection.loadDetail("acme", "web", pr).isEmpty(), "the read-model row is gone");
        assertEquals(0, countBy("SELECT COUNT(*) FROM review_event WHERE review_id = ?", id), "timeline cleared");
        assertEquals(0, countBy("SELECT COUNT(*) FROM event_log WHERE stream_id = ?", id), "event stream cleared");

        assertFalse(projection.deleteReview("acme", "web", pr), "deleting a missing review reports no-op");
    }

    @Test
    void deleteAlsoClearsTheWorkerIdempotencyClaims() throws Exception {
        long pr = 4107L;
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "Rerun me", "rdev", "acc-7",
                "feature", "main", "c0ffee", "https://x/pr/4107", "github", "completed",
                ReviewProjection.STAGE_DONE);

        // Stand in for the worker's own schema/table (the worker migrates it in prod). A claim row
        // for this review must NOT survive delete, else a re-register resurrects the stale result.
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS worker");
            st.execute("CREATE TABLE IF NOT EXISTS worker.comment_idempotency ("
                    + "review_id text NOT NULL, commit text NOT NULL, anchor_key text NOT NULL, "
                    + "comment_id text, posted_at timestamptz, created_at timestamptz DEFAULT now(), "
                    + "PRIMARY KEY (review_id, commit, anchor_key))");
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO worker.comment_idempotency (review_id, commit, anchor_key, comment_id) "
                             + "VALUES (?, 'c0ffee', 'LLM', '{\"usage\":{\"model\":\"stale\"}}')")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        assertEquals(1, countBy("SELECT COUNT(*) FROM worker.comment_idempotency WHERE review_id = ?", id),
                "precondition: the worker claim exists");

        assertTrue(projection.deleteReview("acme", "web", pr));

        assertEquals(0, countBy("SELECT COUNT(*) FROM worker.comment_idempotency WHERE review_id = ?", id),
                "delete clears the worker's idempotency claims so a re-register runs the LLM fresh");
    }

    @Test
    void commitOfReturnsTheStoredHeadShaOrEmpty() {
        long pr = 4108L;
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "T", "u", "a", "f", "main", "sha-head-9",
                "https://x/pr/4108", "github", "completed", ReviewProjection.STAGE_DONE);
        assertEquals("sha-head-9", projection.commitOf(id).orElseThrow());
        assertTrue(projection.commitOf(ReviewIds.reviewId(REPO, 99999L)).isEmpty(), "unknown review -> empty");
    }

    @Test
    void clearWorkerIdempotencyDropsClaimsButKeepsTheReview() throws Exception {
        long pr = 4109L;
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "Keep me", "u", "a", "f", "main", "sha-x",
                "https://x/pr/4109", "github", "completed", ReviewProjection.STAGE_DONE);
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS worker");
            st.execute("CREATE TABLE IF NOT EXISTS worker.comment_idempotency ("
                    + "review_id text NOT NULL, commit text NOT NULL, anchor_key text NOT NULL, "
                    + "comment_id text, posted_at timestamptz, created_at timestamptz DEFAULT now(), "
                    + "PRIMARY KEY (review_id, commit, anchor_key))");
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO worker.comment_idempotency "
                     + "(review_id, commit, anchor_key) VALUES (?, 'sha-x', 'LLM')")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }

        projection.clearWorkerIdempotency(id);

        assertEquals(0, countBy("SELECT COUNT(*) FROM worker.comment_idempotency WHERE review_id = ?", id),
                "the worker claim is cleared");
        assertTrue(projection.loadDetail("acme", "web", pr).isPresent(),
                "but the orchestrator review itself is kept (unlike delete)");
    }

    @Test
    void markRerunStartedResetsStatusAndClearsError() {
        long pr = 4110L;
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "Rerun", "u", "a", "f", "main", "sha",
                "https://x/pr/4110", "github", "failed", ReviewProjection.STAGE_DIFF);
        projection.setError(id, "boom: the model rejected the request");

        projection.markRerunStarted(id);

        ReviewDetail d = projection.loadDetail("acme", "web", pr).orElseThrow();
        assertEquals("reviewing", d.status());
        assertNull(d.errorDetail(), "the prior terminal error is cleared on re-run");
    }

    private int countBy(String sql, String id) throws Exception {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    @Test
    void terminalErrorIsStoredEncryptedSurfacedAndClearedOnRerun() throws Exception {
        long pr = 4107L;
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "Boom", "edev", "acc-6",
                "feature", "main", "err1", "https://x/pr/4107", "github", "reviewing",
                ReviewProjection.STAGE_REVIEW);

        projection.updateStatus(id, "failed", ReviewProjection.STAGE_REVIEW);
        projection.setError(id, "OPENAI-ERROR: 'max_tokens' is not supported with this model.");

        // surfaced on the detail...
        ReviewDetail d = projection.loadDetail("acme", "web", pr).orElseThrow();
        assertEquals("OPENAI-ERROR: 'max_tokens' is not supported with this model.", d.errorDetail());

        // ...but encrypted at rest
        String stored;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT error_detail FROM review_status WHERE review_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                stored = rs.getString("error_detail");
            }
        }
        assertNotNull(stored);
        assertFalse(stored.contains("max_tokens"), "error detail must be encrypted at rest");

        // re-registering (a re-push / new commit) clears the stale error
        projection.registerHeader(id, REPO, pr, "Boom", "edev", "acc-6",
                "feature", "main", "err2", "https://x/pr/4107", "github", "reviewing",
                ReviewProjection.STAGE_DIFF);
        assertNull(projection.loadDetail("acme", "web", pr).orElseThrow().errorDetail(),
                "a fresh run clears the previous error");
    }

    @Test
    void findingsAreEncryptedAtRest() throws Exception {
        long pr = 4103L;
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "t", "a", "aid", "s", "d", "sha", "url",
                "github", "reviewing", ReviewProjection.STAGE_DIFF);
        var result = new ReviewResult(
                List.of(new Finding("src/X.java", new LineRange(1, 1), Severity.MAJOR, "SECRET-MARKER-XYZ", null)),
                "summary", null);
        projection.recordOutcome(id, result, ReviewProjection.STAGE_COMMENTS);

        String stored;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT findings_json FROM review_status WHERE review_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                stored = rs.getString("findings_json");
            }
        }
        assertNotNull(stored);
        assertFalse(stored.contains("SECRET-MARKER-XYZ"), "findings must be encrypted at rest");

        // and it decrypts back through the read API
        assertEquals("SECRET-MARKER-XYZ",
                projection.loadDetail("acme", "web", pr).orElseThrow().findingsList().get(0).msg());
    }

    @Test
    void detailLinksFindingConversationsAndClassifiesTurns() {
        long pr = 4108L;
        String id = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(id, REPO, pr, "Fib", "jlee", "acc-9",
                "fix", "main", "beef999", "https://x/pr/4108", "github", "reviewing",
                ReviewProjection.STAGE_DIFF);

        var result = new ReviewResult(
                List.of(new Finding("src/App.java", new LineRange(9, 9), Severity.BLOCKER, "no compile", null)),
                "One blocker.", new ModelUsage("gpt-x", 10, 5, 100));
        projection.recordOutcome(id, result, ReviewProjection.STAGE_COMMENTS);

        // finding thread c1 (matches src/App.java:9), summary thread sum1
        threads.markFindingThread(id, new ThreadRef("c1"), "src/App.java", 9);
        threads.markSummaryThread(id, new ThreadRef("sum1"));

        projection.appendEvent(id, "integration", "AuthorReplied", "@jlee: why?", "c1");
        projection.appendEvent(id, "result", "FollowUpGenerated", "Because …", "c1");
        projection.appendEvent(id, "integration", "AuthorReplied", "@jlee: overall?", "sum1");
        projection.appendEvent(id, "integration", "AuthorReplied", "@jlee: @bot look here", "m1");
        projection.appendEvent(id, "result", "FollowUpGenerated", "legacy turn"); // 4-arg → null thread_ref

        ReviewDetail d = projection.loadDetail("acme", "web", pr).orElseThrow();

        assertEquals("c1", d.findingsList().get(0).threadRef(), "finding adopts its thread by path:line");
        assertEquals("finding", kindOf(d, "c1"));
        assertEquals("summary", kindOf(d, "sum1"));
        assertEquals("mention", kindOf(d, "m1"), "an @-mention thread on no finding is general");
        assertTrue(d.events().stream().anyMatch(e -> e.threadRef() == null && e.threadKind() == null),
                "a pre-migration turn (null thread_ref) has no kind and falls to General");
    }

    private static String kindOf(ReviewDetail d, String threadRef) {
        return d.events().stream().filter(e -> threadRef.equals(e.threadRef()))
                .map(ReviewDetail.EventView::threadKind).findFirst().orElseThrow();
    }
}
