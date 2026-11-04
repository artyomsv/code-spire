package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.scm.ThreadRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * V111's backfill over history written before the receipt table existed (review of PR #371, code-quality/M-1): a
 * thread counted two answers, A then B, each with its own answer row linked to the root, and no receipts. After the
 * backfill a replay of A counts nothing; without it, the control, it counts again. The migration is idempotent, so the
 * test runs its own SQL again over the seeded rows, against the real schema.
 */
@QuarkusTest
class ReviewThreadTurnBackfillIT {

    private static final String BACKFILL = "db/migration/V111__review_thread_turn_answers.sql";

    @Inject
    ReviewThreadView threads;

    @Inject
    DataSource dataSource;

    private String reviewId;
    private ThreadRef root;

    @BeforeEach
    void seedHistoryFromBeforeTheReceipts() {
        reviewId = "review::TEST-V111/TEST-REPO#" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000);
        root = new ThreadRef("TEST-root");
        exec("INSERT INTO review_thread (review_id, thread_ref, turn_count, last_comment_id, is_ours)"
                + " VALUES (?, ?, 2, 'TEST-answer-B', TRUE)", reviewId, root.value());
        exec("INSERT INTO review_thread (review_id, thread_ref, root_ref, is_ours) VALUES (?, 'TEST-answer-A', ?, TRUE)",
                reviewId, root.value());
        exec("INSERT INTO review_thread (review_id, thread_ref, root_ref, is_ours) VALUES (?, 'TEST-answer-B', ?, TRUE)",
                reviewId, root.value());
    }

    @Test
    void anOlderAnswerReplayedAfterTheBackfillCountsNothing() {
        runBackfill();

        threads.bumpTurn(reviewId, root, "TEST-answer-A");

        assertEquals(2, threads.turnCount(reviewId, root), "A was counted before the receipts existed");
        assertEquals("TEST-answer-B", lastAnswer(), "the replay does not move the last answer back");
        threads.bumpTurn(reviewId, root, "TEST-answer-C");
        assertEquals(3, threads.turnCount(reviewId, root), "a genuinely new answer is a new turn");
    }

    /** The control: the same history with no backfill counts the replay again, so the test above depends on V111. */
    @Test
    void withoutTheBackfillTheReplayWouldCountAgain() {
        threads.bumpTurn(reviewId, root, "TEST-answer-A");

        assertEquals(3, threads.turnCount(reviewId, root));
    }

    private void runBackfill() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(BACKFILL);
             Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            if (in == null) {
                throw new IllegalStateException(BACKFILL + " is not on the classpath");
            }
            st.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("could not run " + BACKFILL, e);
        }
    }

    private String lastAnswer() {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT last_comment_id FROM review_thread WHERE review_id = ? AND thread_ref = ?")) {
            ps.setString(1, reviewId);
            ps.setString(2, root.value());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the thread's last answer", e);
        }
    }

    private void exec(String sql, String... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }
}
