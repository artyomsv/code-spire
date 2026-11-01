package dev.codespire.orchestrator.llm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The round a supersede ended, read from the review's own stream by the supersede's position (review of PR #367). It
 * counts the requests before the supersede and never the one emitted with it, and it does not move when later rounds
 * are appended, so a listener that runs late still cancels the right round.
 */
@QuarkusTest
class ReviewRunsSupersededRoundTest {

    @Inject
    ReviewRuns runs;

    @Inject
    DataSource dataSource;

    /** A forced re-run on one head, then a new head: requested, superseded, requested, superseded, requested. */
    @Test
    void aSupersedeEndsTheRoundOfTheRequestsBeforeIt() {
        String review = "review::TEST-ROUND/TEST-REPO#" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000);
        append(review, 1, "ReviewRequested");
        append(review, 2, "ReviewSuperseded");
        append(review, 3, "ReviewRequested");
        append(review, 4, "ReviewSuperseded");
        append(review, 5, "ReviewRequested");

        assertEquals(1, runs.roundSupersededAt(review, 2), "the forced re-run ended round 1");
        assertEquals(2, runs.roundSupersededAt(review, 4), "the new head ended round 2, read after round 3 began");
        assertEquals(3, runs.currentRun(review), "the same count currentRun gives the round in flight");
    }

    private void append(String stream, long sequence, String type) {
        String sql = "INSERT INTO event_log (event_id, stream_id, sequence, event_type, payload, occurred_at)"
                + " VALUES (?, ?, ?, ?, ?, now())";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, stream);
            ps.setLong(3, sequence);
            ps.setString(4, type);
            ps.setBytes(5, "{}".getBytes(StandardCharsets.UTF_8));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not append a test event", e);
        }
    }
}
