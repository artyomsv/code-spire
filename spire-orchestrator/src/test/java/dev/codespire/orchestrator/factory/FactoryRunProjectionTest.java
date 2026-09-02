package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class FactoryRunProjectionTest {

    @Inject
    FactoryRunProjection projection;

    private String queuedRun() {
        String runId = "run::github:TEST-acme/app:subject-" + UUID.randomUUID() + ":1";
        projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "gpt-5.6", "main", "abc1234", "spire/x", "spire-bot"));
        return runId;
    }

    @Test
    void aRunIsQueuedThenRunningThenSucceeded() {
        String runId = queuedRun();
        assertEquals(FactoryRunProjection.QUEUED, projection.find(runId).orElseThrow().status());

        projection.apply(new RunResult.RunStarted(runId, "container-1"));
        assertEquals(FactoryRunProjection.RUNNING, projection.find(runId).orElseThrow().status());

        projection.apply(new RunResult.RunFinished(runId, "refs/heads/spire/x",
                List.of("src/Foo.java"), List.of(), Map.of("INPUT", 12L)));

        FactoryRunProjection.RunView view = projection.find(runId).orElseThrow();
        assertEquals(FactoryRunProjection.SUCCEEDED, view.status());
        assertEquals("refs/heads/spire/x", view.pushedRef());
    }

    @Test
    void aGateRefusalIsRefusedNotFailed() {
        // The run did correct work that was deliberately not delivered. A status that says "failed"
        // sends an operator hunting for a bug that does not exist; this status sends them to the
        // blocked paths, which is where the answer is.
        String runId = queuedRun();
        projection.apply(new RunResult.RunStarted(runId, "container-1"));

        projection.apply(new RunResult.RunFinished(runId, null, List.of(".github/workflows/ci.yml"),
                List.of(".github/workflows/ci.yml"), null));

        FactoryRunProjection.RunView view = projection.find(runId).orElseThrow();
        assertEquals(FactoryRunProjection.PUSH_GATE_REFUSED, view.status());
        assertEquals(List.of(".github/workflows/ci.yml"), view.blockedPaths());
        assertNull(view.pushedRef());
        assertNull(view.failureCause());
    }

    @Test
    void aFailureNamesItsCause() {
        String runId = queuedRun();

        projection.apply(new RunResult.RunFailed(runId, "SANDBOX_UNREACHABLE", "daemon down", true));

        FactoryRunProjection.RunView view = projection.find(runId).orElseThrow();
        assertEquals(FactoryRunProjection.FAILED, view.status());
        assertEquals("SANDBOX_UNREACHABLE", view.failureCause());
    }

    @Test
    void aRedeliveredResultChangesNothing() {
        // The results channel acks on receipt, so the same RunFinished can arrive twice. The second
        // touches no row — and a redelivered RunStarted after the terminal result must not drag the
        // row back to running.
        String runId = queuedRun();
        RunResult.RunFinished finished = new RunResult.RunFinished(runId, "refs/heads/spire/x",
                List.of(), List.of(), null);

        projection.apply(finished);
        projection.apply(finished);
        projection.apply(new RunResult.RunStarted(runId, "container-1"));

        assertEquals(FactoryRunProjection.SUCCEEDED, projection.find(runId).orElseThrow().status());
    }

    @Test
    void queueingTheSameRunTwiceIsIdempotent() {
        // The resource records the row before dispatching, so a retried request must not fail on
        // the primary key — it must simply find the row already there.
        String runId = queuedRun();
        projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "gpt-5.6", "main", "abc1234", "spire/x", "spire-bot"));

        assertEquals(FactoryRunProjection.QUEUED, projection.find(runId).orElseThrow().status());
    }

    @Test
    void onlyADispatchFailureIsReArmedByQueueingAgain() {
        // The re-arm exists for one state: a row whose dispatch the broker never acknowledged. A run
        // that finished — succeeded, refused, or failed for any other cause — is history, and a
        // repeated request must not quietly reopen it.
        String finished = queuedRun();
        projection.apply(new RunResult.RunFinished(finished, "refs/heads/spire/x", List.of(), List.of(), null));
        projection.queued(new FactoryRunProjection.QueuedRun(finished, "codex", "gpt-5.6", "main", "abc1234", "spire/x", "spire-bot"));
        assertEquals(FactoryRunProjection.SUCCEEDED, projection.find(finished).orElseThrow().status());

        String crashed = queuedRun();
        projection.apply(new RunResult.RunFailed(crashed, "SANDBOX_UNREACHABLE", "daemon down", true));
        projection.queued(new FactoryRunProjection.QueuedRun(crashed, "codex", "gpt-5.6", "main", "abc1234", "spire/x", "spire-bot"));
        assertEquals("SANDBOX_UNREACHABLE", projection.find(crashed).orElseThrow().failureCause());

        String unacked = queuedRun();
        projection.dispatchFailed(unacked, "No broker ack within 10s");
        assertEquals(FactoryRunProjection.FAILED, projection.find(unacked).orElseThrow().status());
        projection.queued(new FactoryRunProjection.QueuedRun(unacked, "codex", "gpt-5.6", "main", "abc1234", "spire/x", "spire-bot"));
        FactoryRunProjection.RunView view = projection.find(unacked).orElseThrow();
        assertEquals(FactoryRunProjection.QUEUED, view.status());
        assertNull(view.failureCause());
    }

    @Test
    void reArmingADispatchFailureTakesTheNewRequestsParameters() {
        // The re-arm used to flip the status and keep the FIRST request's harness, model, commit and
        // branch — while the command actually dispatched carried the SECOND request's. The row then
        // described a run that was never sent, and every later read (attention, the operator's
        // page) lied about what was running.
        String runId = queuedRun();
        projection.dispatchFailed(runId, "No broker ack within 10s");

        projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "gpt-5.7-mini", "release/2",
                "fedcba9876543210fedcba9876543210fedcba98", "spire/x", "other-bot"));

        assertEquals(FactoryRunProjection.QUEUED, projection.find(runId).orElseThrow().status());
        assertEquals(List.of("gpt-5.7-mini", "release/2", "fedcba9876543210fedcba9876543210fedcba98", "other-bot"),
                column(runId, "model", "base_branch", "base_commit", "pushed_as"));
    }

    @Inject
    DataSource dataSource;

    private List<String> column(String runId, String... columns) {
        String sql = "SELECT " + String.join(", ", columns) + " FROM factory_run WHERE run_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("no row for " + runId);
                }
                List<String> values = new ArrayList<>();
                for (int i = 1; i <= columns.length; i++) {
                    values.add(rs.getString(i));
                }
                return values;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    @Test
    void aLateDispatchFailureCannotOverwriteARunThatAlreadyStarted() {
        // The emitter's ack wait is shorter than the producer's delivery timeout, so a slow broker can
        // report "not dispatched" for a record that landed and whose worker already sent RunStarted.
        // Marking that row failed would drop the run's real result, which projects only onto a queued
        // or running row, and let a retry re-arm the row under a run still executing.
        String runId = queuedRun();
        projection.apply(new RunResult.RunStarted(runId, "container-1"));
        projection.dispatchFailed(runId, "No broker ack within 10s");
        assertEquals(FactoryRunProjection.RUNNING, projection.find(runId).orElseThrow().status());
        projection.apply(new RunResult.RunFinished(runId, "refs/heads/spire/x", List.of(), List.of(), null));
        FactoryRunProjection.RunView view = projection.find(runId).orElseThrow();
        assertEquals(FactoryRunProjection.SUCCEEDED, view.status());
        assertNull(view.failureCause());
    }

    @Test
    void aRealResultCorrectsARowWhoseDispatchWasNeverAcknowledged() {
        // DISPATCH_FAILED means the broker's ack timed out, not that the record was lost. The worker
        // consumes one command at a time, so the real RunStarted routinely arrives after the row was
        // marked. Refusing it would drop a run that is executing and paid for.
        String started = queuedRun();
        projection.dispatchFailed(started, "No broker ack within 10s");
        projection.apply(new RunResult.RunStarted(started, "container-1"));
        FactoryRunProjection.RunView view = projection.find(started).orElseThrow();
        assertEquals(FactoryRunProjection.RUNNING, view.status());
        assertNull(view.failureCause(), "the superseded failure is cleared, not left beside a live status");

        // And a terminal result straight onto the marked row, when RunStarted itself was lost.
        String finished = queuedRun();
        projection.dispatchFailed(finished, "No broker ack within 10s");
        projection.apply(new RunResult.RunFinished(finished, "refs/heads/spire/x", List.of("a"), List.of(), null));
        view = projection.find(finished).orElseThrow();
        assertEquals(FactoryRunProjection.SUCCEEDED, view.status());
        assertNull(view.failureCause());

        // But a row that failed for any OTHER cause stays failed: that failure is the run's real end.
        String crashed = queuedRun();
        projection.apply(new RunResult.RunFailed(crashed, "SANDBOX_UNREACHABLE", "daemon down", true));
        projection.apply(new RunResult.RunStarted(crashed, "container-1"));
        assertEquals(FactoryRunProjection.FAILED, projection.find(crashed).orElseThrow().status());
    }

    @Test
    void anUnknownRunIsAbsentNotAnError() {
        assertTrue(projection.find("run::github:TEST-none/x:y:1").isEmpty());
    }
}
