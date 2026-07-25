package dev.codespire.worker.pipeline;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The "never post twice, never silently lose" store (ADR-013 + review finding
 * H1): posted rows are final and reusable; crashed claims (NULL comment_id)
 * are reclaimable.
 */
@QuarkusTest
class CommentIdempotencyStoreTest {

    @Inject
    CommentIdempotencyStore store;

    private String reviewId() {
        return "review::sandbox/idempotency#" + UUID.randomUUID();
    }

    @Test
    void postedSlotIsNeverReclaimedAndReturnsItsCommentId() {
        String reviewId = reviewId();
        assertInstanceOf(CommentIdempotencyStore.Claim.Post.class,
                store.claim(reviewId, "abc", "SUMMARY"));
        store.markPosted(reviewId, "abc", "SUMMARY", "c-991");

        var second = store.claim(reviewId, "abc", "SUMMARY");
        var already = assertInstanceOf(CommentIdempotencyStore.Claim.AlreadyPosted.class, second);
        assertEquals("c-991", already.commentId());
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

        Map<String, CommentIdempotencyStore.PostedSlot> posted = store.postedFor(reviewId, "abc");
        assertEquals(Set.of("SUMMARY", "src/B.java:3:NEW"), posted.keySet());
        assertEquals("c-1", posted.get("SUMMARY").commentId());
        assertEquals("c-2", posted.get("src/B.java:3:NEW").commentId());
    }

    @Test
    void aRecordedThreadSurvivesReconstruction() {
        // GitLab's discussion id differs from its note id, and ownership is keyed by the thread. If only
        // the comment came back here, a retry that rebuilt its inline list would re-introduce the bug
        // where the bot cannot recognize its own thread.
        String reviewId = reviewId();
        store.claim(reviewId, "abc", "src/A.java:1:NEW");
        store.markPosted(reviewId, "abc", "src/A.java:1:NEW", "note-1", "discussion-1");

        var slot = store.postedFor(reviewId, "abc").get("src/A.java:1:NEW");
        assertEquals("note-1", slot.commentId());
        assertEquals("discussion-1", slot.threadRefOrCommentId());

        assertInstanceOf(CommentIdempotencyStore.Claim.AlreadyPosted.class,
                store.claim(reviewId, "abc", "src/A.java:1:NEW"));
    }

    @Test
    void aSlotWithNoRecordedThreadFallsBackToTheComment() {
        // GitHub and Bitbucket make the comment its own thread root, and rows written before the column
        // have no thread — both must keep working.
        String reviewId = reviewId();
        store.claim(reviewId, "abc", "src/A.java:2:NEW");
        store.markPosted(reviewId, "abc", "src/A.java:2:NEW", "c-9");

        assertEquals("c-9", store.postedFor(reviewId, "abc").get("src/A.java:2:NEW").threadRefOrCommentId());
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
}
