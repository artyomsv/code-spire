package dev.codespire.worker.pipeline;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Insert-before-post idempotency for comment posting (ADR-013), owned by the
 * worker (schema-per-service).
 *
 * Semantics (recovery-aware):
 * - a row with {@code comment_id} set is PROOF of a successful post: the slot
 *   is never posted again and the stored id is reused to reconstruct
 *   CommentsPosted on redelivery;
 * - a row with {@code comment_id} NULL means a previous attempt claimed the
 *   slot but crashed before (or during) the post — it is RECLAIMABLE, so the
 *   retry re-posts instead of silently losing the comment.
 */
@ApplicationScoped
public class CommentIdempotencyStore {

    /** Outcome of a claim attempt. */
    public sealed interface Claim {
        /** This caller owns the slot and must post. */
        record Post() implements Claim {
        }

        /** A previous attempt already posted; reuse its comment id. */
        /** threadRef is null for a row written before the column, or an SCM where it equals the comment id. */
        record AlreadyPosted(String commentId, String threadRef) implements Claim {

            /** The id a human's reply will arrive under. */
            String threadRefOrCommentId() {
                return threadRef == null || threadRef.isBlank() ? commentId : threadRef;
            }
        }
    }

    @Inject
    DataSource dataSource;

    public Claim claim(String reviewId, String commit, String anchorKey) {
        String upsert = """
                INSERT INTO comment_idempotency (review_id, commit, anchor_key)
                VALUES (?, ?, ?)
                ON CONFLICT (review_id, commit, anchor_key)
                DO UPDATE SET created_at = now() WHERE comment_idempotency.comment_id IS NULL
                """;
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                ps.setString(1, reviewId);
                ps.setString(2, commit);
                ps.setString(3, anchorKey);
                if (ps.executeUpdate() > 0) {
                    return new Claim.Post(); // inserted, or reclaimed a crashed claim
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT comment_id, thread_ref FROM comment_idempotency WHERE review_id = ? AND commit = ? AND anchor_key = ?")) {
                ps.setString(1, reviewId);
                ps.setString(2, commit);
                ps.setString(3, anchorKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getString(1) != null) {
                        return new Claim.AlreadyPosted(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return new Claim.Post(); // raced row vanished/NULL — post
        } catch (SQLException e) {
            throw new IllegalStateException("comment_idempotency claim failed", e);
        }
    }

    public void markPosted(String reviewId, String commit, String anchorKey, String commentId) {
        markPosted(reviewId, commit, anchorKey, commentId, null);
    }

    /** Records the thread too, so a reconstruction after a partially-completed attempt keeps the id a
     *  reply will arrive under — on GitLab that is the discussion, not the note. */
    public void markPosted(String reviewId, String commit, String anchorKey, String commentId,
                           String threadRef) {
        String sql = """
                UPDATE comment_idempotency SET comment_id = ?, thread_ref = ?, posted_at = now()
                WHERE review_id = ? AND commit = ? AND anchor_key = ?
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, commentId);
            ps.setString(2, threadRef);
            ps.setString(3, reviewId);
            ps.setString(4, commit);
            ps.setString(5, anchorKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("comment_idempotency update failed", e);
        }
    }

    /** A slot already posted by an earlier attempt. {@code threadRef} may be null — see {@link #markPosted}. */
    public record PostedSlot(String commentId, String threadRef) {

        /** The id a human's reply will arrive under. */
        public String threadRefOrCommentId() {
            return threadRef == null || threadRef.isBlank() ? commentId : threadRef;
        }
    }

    /** anchorKey -> what was posted there, for every completed slot of this run (redelivery
     *  reconstruction). Carries the thread as well as the comment, because ownership is keyed by thread. */
    public Map<String, PostedSlot> postedFor(String reviewId, String commit) {
        String sql = """
                SELECT anchor_key, comment_id, thread_ref FROM comment_idempotency
                WHERE review_id = ? AND commit = ? AND comment_id IS NOT NULL
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            ps.setString(2, commit);
            Map<String, PostedSlot> posted = new LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    posted.put(rs.getString(1), new PostedSlot(rs.getString(2), rs.getString(3)));
                }
            }
            return posted;
        } catch (SQLException e) {
            throw new IllegalStateException("comment_idempotency read failed", e);
        }
    }
}
