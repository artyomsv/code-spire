package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

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
        projection.queued(runId, "codex", "gpt-5.6", "main", "abc1234", "spire/x", "spire-bot");
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
        projection.queued(runId, "codex", "gpt-5.6", "main", "abc1234", "spire/x", "spire-bot");

        assertEquals(FactoryRunProjection.QUEUED, projection.find(runId).orElseThrow().status());
    }

    @Test
    void onlyADispatchFailureIsReArmedByQueueingAgain() {
        // The re-arm exists for one state: a row whose dispatch the broker never acknowledged. A run
        // that finished — succeeded, refused, or failed for any other cause — is history, and a
        // repeated request must not quietly reopen it.
        String finished = queuedRun();
        projection.apply(new RunResult.RunFinished(finished, "refs/heads/spire/x", List.of(), List.of(), null));
        projection.queued(finished, "codex", "gpt-5.6", "main", "abc1234", "spire/x", "spire-bot");
        assertEquals(FactoryRunProjection.SUCCEEDED, projection.find(finished).orElseThrow().status());

        String crashed = queuedRun();
        projection.apply(new RunResult.RunFailed(crashed, "SANDBOX_UNREACHABLE", "daemon down", true));
        projection.queued(crashed, "codex", "gpt-5.6", "main", "abc1234", "spire/x", "spire-bot");
        assertEquals("SANDBOX_UNREACHABLE", projection.find(crashed).orElseThrow().failureCause());

        String unacked = queuedRun();
        projection.dispatchFailed(unacked, "No broker ack within 10s");
        assertEquals(FactoryRunProjection.FAILED, projection.find(unacked).orElseThrow().status());
        projection.queued(unacked, "codex", "gpt-5.6", "main", "abc1234", "spire/x", "spire-bot");
        FactoryRunProjection.RunView view = projection.find(unacked).orElseThrow();
        assertEquals(FactoryRunProjection.QUEUED, view.status());
        assertNull(view.failureCause());
    }

    @Test
    void anUnknownRunIsAbsentNotAnError() {
        assertTrue(projection.find("run::github:TEST-none/x:y:1").isEmpty());
    }
}
