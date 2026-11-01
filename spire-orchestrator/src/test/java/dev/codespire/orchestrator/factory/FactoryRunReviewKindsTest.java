package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V107: a review run's row names its review, its call slot and its pull request, and nothing else may. Written as raw
 * SQL for the reason {@code FixRunsTest} gives: the point is that the SCHEMA holds the rule, not one careful writer.
 */
@QuarkusTest
class FactoryRunReviewKindsTest {

    private static final String REVIEW = "review::TEST-WS/TEST-REPO#7301";

    @Inject
    DataSource dataSource;

    /** Every NOT NULL column, plus the four a review row must carry. */
    @Test
    void aReviewRunRowIsAdmitted() {
        for (String kind : new String[] {"REVIEW", "RECONCILE", "FOLLOWUP"}) {
            assertDoesNotThrow(() -> insert(new Row(runId(kind), kind, REVIEW, "TEST-slot", 7L, 1)), kind);
        }
    }

    @Test
    void anUnknownKindIsStillRefused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> insert(new Row(runId("unknown"), "TEST-UNKNOWN", null, null, null, null)));

        assertTrue(rootCause(refused).contains("factory_run_kind_closed"), rootCause(refused));
    }

    @Test
    void aReviewRowWithoutItsReviewIsRefused() {
        for (String review : new String[] {null, " "}) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> insert(new Row(runId("no-review"), "REVIEW", review, "TEST-slot", 7L, 1)), String.valueOf(review));

            assertTrue(rootCause(refused).contains("factory_run_fix_names_its_target"), rootCause(refused));
        }
    }

    @Test
    void aReviewRowWithoutItsSlotIsRefused() {
        IllegalStateException noSlot = assertThrows(IllegalStateException.class,
                () -> insert(new Row(runId("no-slot"), "RECONCILE", REVIEW, null, 7L, 1)));
        IllegalStateException noPullRequest = assertThrows(IllegalStateException.class,
                () -> insert(new Row(runId("no-pr"), "RECONCILE", REVIEW, "TEST-slot", null, 1)));

        assertTrue(rootCause(noSlot).contains("factory_run_review_names_its_call"), rootCause(noSlot));
        assertTrue(rootCause(noPullRequest).contains("factory_run_review_names_its_call"), rootCause(noPullRequest));
    }

    /** The other direction: a build carrying a review call would be counted where it never answered one. */
    @Test
    void aBuildRowCarriesNoReviewCall() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> insert(new Row(runId("build"), "BUILD", null, "TEST-slot", 7L, null)));

        assertTrue(rootCause(refused).contains("factory_run_review_names_its_call"), rootCause(refused));
        assertDoesNotThrow(() -> insert(new Row(runId("build-plain"), "BUILD", null, null, null, null)));
    }

    /**
     * A review row names the round it belongs to (review of PR #367): a supersede ends a round, and the admission and the
     * cancel compare it. A round below 1 is no round, and any other kind carries none.
     */
    @Test
    void aReviewRowWithoutItsRoundIsRefused() {
        IllegalStateException noRound = assertThrows(IllegalStateException.class,
                () -> insert(new Row(runId("no-round"), "FOLLOWUP", REVIEW, "TEST-slot", 7L, null)));
        IllegalStateException zeroRound = assertThrows(IllegalStateException.class,
                () -> insert(new Row(runId("zero-round"), "REVIEW", REVIEW, "TEST-slot", 7L, 0)));
        IllegalStateException buildRound = assertThrows(IllegalStateException.class,
                () -> insert(new Row(runId("build-round"), "BUILD", null, null, null, 1)));

        assertTrue(rootCause(noRound).contains("factory_run_review_names_its_call"), rootCause(noRound));
        assertTrue(rootCause(zeroRound).contains("factory_run_review_names_its_call"), rootCause(zeroRound));
        assertTrue(rootCause(buildRound).contains("factory_run_review_names_its_call"), rootCause(buildRound));
    }

    private record Row(String runId, String kind, String reviewId, String callSlot, Long prId, Integer round) {
    }

    private static String runId(String what) {
        return "run::github:TEST-V107/TEST-REPO:TEST-" + what + "-" + UUID.randomUUID() + ":1";
    }

    private void insert(Row row) {
        String sql = """
                INSERT INTO factory_run (run_id, provider_type, workspace, slug, subject, attempt,
                                         status, harness, model, base_branch, base_commit, branch,
                                         kind, review_id, review_call_slot, review_pr_id, review_round)
                VALUES (?, 'github', 'TEST-V107', 'TEST-REPO', 'TEST-SUBJECT', 1,
                        'queued', 'codex', 'TEST-MODEL', 'main', 'TESTSHA0', 'spire/TEST-SUBJECT',
                        ?, ?, ?, ?, ?)
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, row.runId());
            ps.setString(2, row.kind());
            ps.setString(3, row.reviewId());
            ps.setString(4, row.callSlot());
            ps.setObject(5, row.prId(), Types.BIGINT);
            ps.setObject(6, row.round(), Types.INTEGER);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }
}
