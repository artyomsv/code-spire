package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The header fence against the real schema (V121): a run's header is written once per round, so a caller whose header
 * write comes after the sweep readied its run and a red read skipped it never resets the status or the run token. A run
 * still admitted, and a newer round on the same head (a re-run started at once), get their header as before; an
 * earlier run's late write never replaces a later run's header.
 */
@QuarkusTest
class ReviewHeaderFenceTest {

    private static final String HEAD = "a".repeat(40);

    @Inject ReviewProjection projection;
    @Inject DataSource dataSource;

    private final String workspace = "TEST-" + UUID.randomUUID();
    private final RepoRef repo = new RepoRef(workspace, "TEST-repo");
    private final String reviewId = "github:" + workspace + "/TEST-repo#7";

    @BeforeEach
    void seedReview() throws Exception {
        execute("INSERT INTO review_status (review_id, workspace, slug, pr_id, status, run_token)"
                + " VALUES (?, ?, 'TEST-repo', 7, 'skipped', 'TEST-token')", reviewId, workspace);
        appendRequest(1);
    }

    @AfterEach
    void removeReview() throws Exception {
        execute("DELETE FROM review_status WHERE workspace = ?", workspace);
        execute("DELETE FROM event_log WHERE stream_id = ?", reviewId);
    }

    /**
     * The sweep readied run 1 (which set the header's round) and it was skipped: the late caller's header write for the
     * same round changes nothing it owns.
     */
    @Test
    void aLateHeaderWriteNeverResetsARunTheSweepResolved() throws Exception {
        execute("INSERT INTO review_checks_wait (review_id, repository_id, head_commit, round, untrusted, state,"
                + " waiting_since, next_read_at) VALUES (?, ?, ?, 1, false, 'skipped', now(), now())",
                reviewId, UUID.randomUUID(), HEAD);
        execute("UPDATE review_status SET run_round = 1 WHERE review_id = ?", reviewId);
        registerHeader(1);
        assertEquals("skipped:TEST-token", statusAndToken());
    }

    /** The caller in time: run 1 is still admitted, so its header and fresh token are written as before. */
    @Test
    void anAdmittedRunGetsItsHeader() throws Exception {
        execute("INSERT INTO review_checks_wait (review_id, repository_id, head_commit, round, untrusted, state,"
                + " waiting_since, next_read_at) VALUES (?, ?, ?, 1, false, 'admitted', now(), now())",
                reviewId, UUID.randomUUID(), HEAD);
        registerHeader(1);
        assertEquals("reviewing", statusAndToken().split(":")[0]);
        assertNotEquals("TEST-token", statusAndToken().split(":")[1]);
    }

    /** Run 1 timed out; run 2 re-ran the same head at once (no row of its own): run 1's row does not fence run 2. */
    @Test
    void anEarlierRoundsRowNeverFencesALaterRun() throws Exception {
        execute("INSERT INTO review_checks_wait (review_id, repository_id, head_commit, round, untrusted, state,"
                + " waiting_since, next_read_at) VALUES (?, ?, ?, 1, false, 'timed_out', now(), now())",
                reviewId, UUID.randomUUID(), HEAD);
        appendRequest(2);
        registerHeader(2);
        assertEquals("reviewing", statusAndToken().split(":")[0]);
    }

    /**
     * Run 1 was admitted to wait and its caller paused before its header write; the repository switched to
     * "Immediately" and run 2 re-ran the same head, writing its header (round 2). The late write of run 1 is fenced by
     * the row it would write: run 2's status and token stay.
     */
    @Test
    void aLateHeaderWriteNeverReplacesALaterRunsHeader() throws Exception {
        execute("INSERT INTO review_checks_wait (review_id, repository_id, head_commit, round, untrusted, state,"
                + " waiting_since, next_read_at) VALUES (?, ?, ?, 1, false, 'admitted', now(), now())",
                reviewId, UUID.randomUUID(), HEAD);
        appendRequest(2);
        registerHeader(2);
        String later = statusAndToken();
        registerHeader(1);
        assertEquals(later, statusAndToken());
    }

    private void registerHeader(int round) {
        projection.registerRunHeader(new ReviewProjection.RunHeader(reviewId, round, repo, 7L, "TEST-title",
                "TEST-author", "TEST-1", "TEST-feature", "main", HEAD, "https://forge.example.test/TEST/7", "github"));
    }

    private void appendRequest(int sequence) throws Exception {
        execute("INSERT INTO event_log (event_id, stream_id, sequence, event_type, payload, occurred_at)"
                + " VALUES (?, ?, ?, 'ReviewRequested', '\\x7b7d'::bytea, now())", UUID.randomUUID(), reviewId,
                (long) sequence);
    }

    private String statusAndToken() throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT status, run_token FROM review_status WHERE review_id = ?")) {
            statement.setString(1, reviewId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1) + ":" + rows.getString(2);
            }
        }
    }

    private void execute(String sql, Object... parameters) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            statement.executeUpdate();
        }
    }
}
