package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.scm.ThreadLocation;
import dev.codespire.contract.scm.ThreadRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Lightweight per-thread state (spec §5): turn count + ownership. No conversation text is stored. */
@ApplicationScoped
public class ReviewThreadView {

    @Inject
    DataSource dataSource;

    public int turnCount(String reviewId, ThreadRef thread) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT turn_count FROM review_thread WHERE review_id = ? AND thread_ref = ?")) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read thread turn count", e);
        }
    }

    public void bumpTurn(String reviewId, ThreadRef thread, String lastCommentId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO review_thread (review_id, thread_ref, turn_count, last_comment_id)
                     VALUES (?, ?, 1, ?)
                     ON CONFLICT (review_id, thread_ref)
                     DO UPDATE SET turn_count = review_thread.turn_count + 1,
                                   last_comment_id = EXCLUDED.last_comment_id
                     """)) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            ps.setString(3, lastCommentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to bump thread turn count", e);
        }
    }

    /**
     * The conversation root for a thread ref. On SCMs that thread by immediate parent (Bitbucket), a
     * reply to the bot's own answer carries the ANSWER's comment id; {@code root_ref} maps it back to
     * the thread it belongs to, so turn counting, event attribution and the reply target all key off
     * one stable id per conversation (as GitHub's {@code in_reply_to_id} already gives us). An unknown
     * ref, or a row with no {@code root_ref}, IS its own root.
     */
    public ThreadRef rootOf(String reviewId, ThreadRef thread) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT root_ref FROM review_thread WHERE review_id = ? AND thread_ref = ?")) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            try (ResultSet rs = ps.executeQuery()) {
                String root = rs.next() ? rs.getString(1) : null;
                return root == null || root.isBlank() ? thread : new ThreadRef(root);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve the conversation root thread", e);
        }
    }

    /**
     * Record the bot's own answer comment as an owned thread that belongs to {@code root} — so a human
     * reply landing on that answer resolves back to the same conversation.
     */
    public void markAnswerThread(String reviewId, ThreadRef answer, ThreadRef root) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO review_thread (review_id, thread_ref, is_ours, root_ref)
                     VALUES (?, ?, TRUE, ?)
                     ON CONFLICT (review_id, thread_ref)
                     DO UPDATE SET is_ours = TRUE, root_ref = EXCLUDED.root_ref
                     """)) {
            ps.setString(1, reviewId);
            ps.setString(2, answer.value());
            ps.setString(3, root.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark the bot's answer thread", e);
        }
    }

    public void markOurThread(String reviewId, ThreadRef thread) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO review_thread (review_id, thread_ref, is_ours) VALUES (?, ?, TRUE)
                     ON CONFLICT (review_id, thread_ref) DO UPDATE SET is_ours = TRUE
                     """)) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark owned thread", e);
        }
    }

    /**
     * Record where a thread sits in the diff, without claiming ownership.
     *
     * <p>For threads a HUMAN started on a line (an @-mention on unflagged code, a fresh thread beside
     * a finding). Only {@code markFindingThread} used to write {@code (path, line)}, so such a thread
     * had no location at all and the UI filed a comment demonstrably attached to a line under
     * "General discussion". {@code COALESCE} keeps an existing location rather than overwriting it,
     * since a finding's own anchor is the more authoritative of the two.
     *
     * <p>Deliberately does NOT set {@code is_finding}: this thread sits on a line, but it is not the
     * thread a reconciliation verdict may reply to or resolve (V27).
     */
    public void markThreadLocation(String reviewId, ThreadRef thread, String path, int line) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO review_thread (review_id, thread_ref, path, line) VALUES (?, ?, ?, ?)
                     ON CONFLICT (review_id, thread_ref)
                     DO UPDATE SET path = COALESCE(review_thread.path, EXCLUDED.path),
                                   line = COALESCE(review_thread.line, EXCLUDED.line)
                     """)) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            ps.setString(3, path);
            ps.setInt(4, line);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record thread location", e);
        }
    }

    /**
     * Record a finding's owned thread together with its {@code (path, line)}.
     *
     * <p>{@code is_finding} is what separates this from {@link #markThreadLocation}: both write a
     * location, but only a thread the bot opened FOR a finding may be the one a verdict targets. A
     * human thread on the same line must never win that lookup (V27).
     */
    public void markFindingThread(String reviewId, ThreadRef thread, String path, int line) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO review_thread (review_id, thread_ref, is_ours, path, line, is_finding)
                     VALUES (?, ?, TRUE, ?, ?, TRUE)
                     ON CONFLICT (review_id, thread_ref)
                     DO UPDATE SET is_ours = TRUE, path = EXCLUDED.path, line = EXCLUDED.line,
                                   is_finding = TRUE
                     """)) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            ps.setString(3, path);
            ps.setInt(4, line);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark finding thread", e);
        }
    }

    /** Flag the summary comment's thread. Display-only: does NOT set is_ours, so conversation
     *  scope (which threads the bot answers) is unchanged. */
    public void markSummaryThread(String reviewId, ThreadRef thread) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO review_thread (review_id, thread_ref, is_summary) VALUES (?, ?, TRUE)
                     ON CONFLICT (review_id, thread_ref) DO UPDATE SET is_summary = TRUE
                     """)) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark summary thread", e);
        }
    }

    /** Flag a thread resolved (ADR-019 — a follow-up review confirmed the finding is fixed). */
    public void markResolved(String reviewId, ThreadRef thread) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO review_thread (review_id, thread_ref, resolved) VALUES (?, ?, TRUE)
                     ON CONFLICT (review_id, thread_ref) DO UPDATE SET resolved = TRUE
                     """)) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark thread resolved", e);
        }
    }

    /**
     * Where a thread sits in the diff, as {@code markThreadLocation}/{@code markFindingThread}
     * recorded it — null for a thread with no row, or a row with no location (a summary or top-level
     * comment, and every row written before V17 added the columns).
     *
     * <p>Answers the question a {@code /finding} asks when the command's own event carried no
     * location: not every provider reports one on every comment surface. Pass the conversation ROOT
     * — a reply's own ref is the branch, and only the root carries the anchor.
     *
     * <p>{@link ThreadLocation#of} is what rejects a half-written row: it answers null unless both
     * parts are present, and its {@code line <= 0} test already covers a NULL line, which
     * {@code getInt} reports as 0. The explicit {@code wasNull} read is not what makes that work —
     * it is there so the SQL boundary says NULL where it means NULL rather than leaning on that
     * coincidence. It must be read immediately after its own {@code getInt}: arguments evaluate
     * left to right, so reading it after a {@code getString} would report on the STRING's column.
     */
    public ThreadLocation locationOf(String reviewId, ThreadRef thread) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT path, line FROM review_thread WHERE review_id = ? AND thread_ref = ?")) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                int line = rs.getInt("line");
                boolean lineIsNull = rs.wasNull();
                return ThreadLocation.of(rs.getString("path"), lineIsNull ? null : line);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read thread location", e);
        }
    }

    public boolean isOurThread(String reviewId, ThreadRef thread) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT is_ours FROM review_thread WHERE review_id = ? AND thread_ref = ?")) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read thread ownership", e);
        }
    }
}
