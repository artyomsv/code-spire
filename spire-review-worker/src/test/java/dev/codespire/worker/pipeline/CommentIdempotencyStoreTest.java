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
     * The desired verdict label (V6) as the orchestrator writes it (its upsert, run here on the worker's schema): a later
     * round replaces it, the same round updates it, and an earlier round's late write changes nothing.
     */
    @Test
    void theDesiredLabelNeverMovesBackToAnEarlierRun() throws Exception {
        String reviewId = reviewId();
        org.junit.jupiter.api.Assertions.assertTrue(store.desiredLabel(reviewId).isEmpty());
        desire(reviewId, 2, null);
        desire(reviewId, 2, "review: approved");
        desire(reviewId, 1, "review: ci-failed");
        assertEquals(new CommentIdempotencyStore.DesiredLabel(2, "review: approved"), store.desiredLabel(reviewId).orElseThrow());
        desire(reviewId, 3, null);
        assertEquals(new CommentIdempotencyStore.DesiredLabel(3, null), store.desiredLabel(reviewId).orElseThrow());
    }

    private void desire(String reviewId, int round, String label) throws Exception {
        try (java.sql.Connection c = dataSource.getConnection(); java.sql.PreparedStatement ps = c.prepareStatement(
                "INSERT INTO review_label_desired (review_id, round, label) VALUES (?, ?, ?)"
                        + " ON CONFLICT (review_id) DO UPDATE SET round = EXCLUDED.round, label = EXCLUDED.label,"
                        + " updated_at = now() WHERE review_label_desired.round <= EXCLUDED.round")) {
            ps.setString(1, reviewId);
            ps.setInt(2, round);
            ps.setString(3, label);
            ps.executeUpdate();
        }
    }
}
