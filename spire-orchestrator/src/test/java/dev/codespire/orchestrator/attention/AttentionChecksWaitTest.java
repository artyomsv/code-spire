package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bell and review waits for CI (V121): a skipped review is finished, a review waiting for its checks is expected to
 * sit until CI finishes or the 60-minute timeout, and a check read that failed is said at once.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class AttentionChecksWaitTest {

    private static final String STUCK = "REVIEW_STUCK";
    private static final String UNREADABLE = ChecksUnreadableAttentionRows.CODE;

    @Inject AttentionQueries queries;
    @Inject DataSource dataSource;

    private final String workspace = "TEST-" + UUID.randomUUID();

    @AfterEach
    void removeReviews() {
        sql("DELETE FROM review_status WHERE workspace = ?", workspace);
    }

    @Test
    void anOldSkippedReviewIsNotStalled() {
        review(1, "skipped", "2 hours");
        assertTrue(rows(STUCK, 1).isEmpty());
    }

    @Test
    void aReviewWaitingThirtyMinutesForItsChecksIsNotStalled() {
        review(2, "waiting_for_checks", "30 minutes");
        assertTrue(rows(STUCK, 2).isEmpty());
    }

    /** Past the timeout, a missed sweep and the stuck limit, a wait has stopped moving. */
    @Test
    void aReviewWaitingPastTheTimeoutMarginIsStalled() {
        review(3, "waiting_for_checks", "3 hours");
        assertEquals(1, rows(STUCK, 3).size());
    }

    /** Still caught at the ordinary limit: only the waiting status gets the margin. */
    @Test
    void aReviewingReviewIsStalledAtTheOrdinaryLimit() {
        review(4, "reviewing", "30 minutes");
        assertEquals(1, rows(STUCK, 4).size());
    }

    @Test
    void aWaitWhoseChecksCouldNotBeReadRaisesAWarningWithTheReason() {
        String reviewId = review(5, "waiting_for_checks", "1 minute");
        wait(reviewId, "waiting", "HTTP 403");
        List<AttentionView> rows = rows(UNREADABLE, 5);
        assertEquals(1, rows.size(), rows.toString());
        assertEquals(AttentionView.Severity.WARNING, rows.getFirst().severity());
        assertTrue(rows.getFirst().message().contains("(HTTP 403)"), rows.getFirst().message());
    }

    @Test
    void aWaitWhoseLastReadAnsweredOrThatEndedRaisesNothing() {
        wait(review(6, "waiting_for_checks", "1 minute"), "waiting", null);
        wait(review(7, "reviewing", "1 minute"), "timed_out", "HTTP 403");
        assertTrue(rows(UNREADABLE, 6).isEmpty());
        assertTrue(rows(UNREADABLE, 7).isEmpty());
    }

    private List<AttentionView> rows(String code, long pr) {
        String subject = workspace + "/TEST-REPO#" + pr;
        return queries.collect().stream().filter(v -> code.equals(v.code()) && subject.equals(v.subject())).toList();
    }

    private String review(long pr, String status, String age) {
        String reviewId = "github:" + workspace + "/TEST-REPO#" + pr;
        sql("INSERT INTO review_status (review_id, workspace, slug, pr_id, status, pr_state, created_at, updated_at)"
                + " VALUES (?, ?, 'TEST-REPO', ?, ?, 'OPEN', now() - ?::interval, now() - ?::interval)",
                reviewId, workspace, pr, status, age, age);
        return reviewId;
    }

    private void wait(String reviewId, String state, String readFailure) {
        sql("INSERT INTO review_checks_wait (review_id, repository_id, head_commit, round, untrusted, state,"
                + " waiting_since, next_read_at, read_failure) VALUES (?, ?, 'TEST-head', 1, false, ?, now(), now(), ?)",
                reviewId, UUID.randomUUID(), state, readFailure);
    }

    private void sql(String statement, Object... parameters) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            for (int i = 0; i < parameters.length; i++) s.setObject(i + 1, parameters[i]);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + statement, e);
        }
    }
}
