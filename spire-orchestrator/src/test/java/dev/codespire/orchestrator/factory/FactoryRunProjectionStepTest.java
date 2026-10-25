package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run row records what the run was for and which plan step it built (A18): {@code kind} and
 * {@code step_index}, written by the one writer every dispatch goes through.
 */
@QuarkusTest
class FactoryRunProjectionStepTest {

    @Inject
    FactoryRunProjection projection;

    @Inject
    DataSource dataSource;

    @Test
    void queuedWritesKindAndStepIndex() {
        String plan = runId("plan");
        assertTrue(projection.queued(base(plan).ofKind(RunKind.PLAN), null, null));
        String step = runId("step");
        assertTrue(projection.queued(base(step).ofKind(RunKind.BUILD).atStep(2), null, null));

        assertEquals(Arrays.asList("PLAN", null), kindAndStep(plan), "a plan run is not a plan step");
        assertEquals(List.of("BUILD", "2"), kindAndStep(step));
    }

    /**
     * The step is part of what the run IS, like its kind: the identical retry re-arms and keeps it, and a
     * retry naming another step is a different request, refused, leaving the row's own step in place.
     */
    @Test
    void aRearmedRunKeepsItsStepIndex() {
        String runId = runId("rearm");
        assertTrue(projection.queued(base(runId).atStep(2), null, null));
        projection.dispatchFailed(runId, "TEST-no broker ack");

        assertFalse(projection.queued(base(runId).atStep(3), null, null),
                "a retry for another step must not re-arm this run's row");
        assertEquals(List.of("BUILD", "2"), kindAndStep(runId));

        assertTrue(projection.queued(base(runId).atStep(2), null, null), "the identical retry re-arms");
        assertEquals(List.of("BUILD", "2"), kindAndStep(runId));
    }

    private static String runId(String what) {
        return "run::github:TEST-WS/TEST-REPO:TEST-" + what + "-" + UUID.randomUUID() + ":1";
    }

    private static FactoryRunProjection.QueuedRun base(String runId) {
        return new FactoryRunProjection.QueuedRun(runId, "codex", "TEST-MODEL", "main", "TESTSHA0",
                "spire/TEST-branch", "TEST-bot", null);
    }

    private List<String> kindAndStep(String runId) {
        String sql = "SELECT kind, step_index FROM factory_run WHERE run_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no row for " + runId);
                return Arrays.asList(rs.getString("kind"), rs.getString("step_index"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read back the run row", e);
        }
    }
}
