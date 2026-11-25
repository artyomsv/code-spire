package dev.codespire.worker.pipeline;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The "never post twice, never silently lose" store (ADR-013 + review finding
 * H1): posted rows are final and reusable; crashed claims (NULL posted_ref)
 * are reclaimable.
 */
@QuarkusTest
class CommentIdempotencyStoreTest {

    @Inject
    CommentIdempotencyStore store;

    @Inject
    javax.sql.DataSource dataSource;

    private String reviewId() {
        return "review::sandbox/idempotency#" + UUID.randomUUID();
    }

    @Test
    void postedSlotIsNeverReclaimedAndReturnsItsRef() {
        String reviewId = reviewId();
        assertInstanceOf(CommentIdempotencyStore.Claim.Post.class,
                store.claim(reviewId, "abc", "SUMMARY"));
        store.markPosted(reviewId, "abc", "SUMMARY", "c-991");

        var second = store.claim(reviewId, "abc", "SUMMARY");
        var already = assertInstanceOf(CommentIdempotencyStore.Claim.AlreadyPosted.class, second);
        assertEquals("c-991", already.postedRef());
    }

    @Test
    void crashedClaimIsReclaimable() {
        String reviewId = reviewId();
        // first attempt claims but "crashes" before markPosted
        assertInstanceOf(CommentIdempotencyStore.Claim.Post.class,
                store.claim(reviewId, "abc", "src/A.java:7:NEW"));
        // the retry must be allowed to post rather than silently losing the comment
        assertInstanceOf(CommentIdempotencyStore.Claim.Post.class,
                store.claim(reviewId, "abc", "src/A.java:7:NEW"));
    }

    @Test
    void postedForReconstructsOnlySuccessfulPosts() {
        String reviewId = reviewId();
        store.claim(reviewId, "abc", "SUMMARY");
        store.markPosted(reviewId, "abc", "SUMMARY", "c-1");
        store.claim(reviewId, "abc", "src/A.java:7:NEW");   // never marked posted
        store.claim(reviewId, "abc", "src/B.java:3:NEW");
        store.markPosted(reviewId, "abc", "src/B.java:3:NEW", "c-2");

        Map<String, String> posted = store.postedFor(reviewId, "abc");
        assertEquals(Map.of("SUMMARY", "c-1", "src/B.java:3:NEW", "c-2"), posted);
    }

    @Test
    void theRecordedRefSurvivesReconstruction() {
        // An inline slot records the THREAD a reply will arrive under — a comment id on GitHub and
        // Bitbucket, a discussion id on GitLab. The store keeps whatever the adapter handed it and
        // nothing here needs to know which kind it is; that is the point.
        String reviewId = reviewId();
        store.claim(reviewId, "abc", "src/A.java:1:NEW");
        store.markPosted(reviewId, "abc", "src/A.java:1:NEW", "discussion-1");

        assertEquals("discussion-1", store.postedFor(reviewId, "abc").get("src/A.java:1:NEW"));
        assertInstanceOf(CommentIdempotencyStore.Claim.AlreadyPosted.class,
                store.claim(reviewId, "abc", "src/A.java:1:NEW"));
    }

    @Test
    void slotsAreScopedPerCommit() {
        String reviewId = reviewId();
        store.claim(reviewId, "abc", "SUMMARY");
        store.markPosted(reviewId, "abc", "SUMMARY", "c-1");
        // a forced re-review of a NEW commit gets fresh slots
        assertInstanceOf(CommentIdempotencyStore.Claim.Post.class,
                store.claim(reviewId, "def", "SUMMARY"));
    }

    /**
     * Review waits for CI: the ci-failed receipt the orchestrator opens (an unposted row) is taken once, never when it
     * is missing; the orchestrator's close (its SQL, run here on the worker's own schema) wins over a later take and
     * closes a taken one too, and an outcome is recorded only over its own taken mark (false once closed).
     */
    @Test
    void aReceiptIsTakenOnlyWhileOpenAndClosingHitsOpenAndTakenOnes() throws Exception {
        String reviewId = reviewId();
        ReceiptKey first = new ReceiptKey(reviewId, "abc", "CI_FAILED:1");
        ReceiptKey second = new ReceiptKey(reviewId, "abc", "CI_FAILED:2");
        org.junit.jupiter.api.Assertions.assertFalse(store.take(first), "not opened");
        store.claim(reviewId, "abc", "CI_FAILED:1");
        store.claim(reviewId, "abc", "CI_FAILED:2");
        store.claim(reviewId, "abc", "CIXFAILED:3");
        org.junit.jupiter.api.Assertions.assertTrue(store.take(second));
        try (var c = dataSource.getConnection(); var ps = c.prepareStatement("UPDATE comment_idempotency"
                + " SET posted_ref = 'superseded', posted_at = now()"
                + " WHERE review_id = ? AND anchor_key LIKE 'CI\\_FAILED:%'"
                + " AND (posted_ref IS NULL OR posted_ref = 'taken')")) {
            ps.setString(1, reviewId);
            ps.executeUpdate();
        }
        org.junit.jupiter.api.Assertions.assertFalse(store.take(first), "closed first");
        org.junit.jupiter.api.Assertions.assertFalse(store.markTaken(first, "APPLIED"));
        org.junit.jupiter.api.Assertions.assertFalse(store.markTaken(second, "APPLIED"), "closed while its label ran");
        Map<String, String> posted = store.postedFor(reviewId, "abc");
        assertEquals("superseded", posted.get("CI_FAILED:1"), "a closed receipt is never overwritten");
        assertEquals("superseded", posted.get("CI_FAILED:2"), "a taken receipt is closed too, so its label is undone");
        org.junit.jupiter.api.Assertions.assertFalse(posted.containsKey("CIXFAILED:3"), "the underscore is literal");
    }
}
