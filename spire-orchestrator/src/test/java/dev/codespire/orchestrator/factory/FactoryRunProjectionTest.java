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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class FactoryRunProjectionTest {

    @Inject
    FactoryRunProjection projection;

    @Test
    void aRunThatDeliveredNothingIsNotRecordedAsSucceeded() {
        // The agent exited cleanly and committed nothing. A legitimate outcome, and the walking
        // skeleton produces it for a script that commits nothing — but recorded as 'succeeded' it
        // was indistinguishable in the list from a run whose branch is on the remote, and any
        // "runs succeeded" number counted both.
        //
        // This also proves V47 actually replaced the status CHECK. That constraint was unnamed in
        // V43, so the migration drops it by the name Postgres gives it; if that guess were wrong the
        // old constraint would survive and this insert would be refused.
        String delivered = queuedRun();
        projection.apply(new RunResult.RunFinished(delivered, "refs/heads/spire/x", List.of(), List.of(), null, false));

        String empty = queuedRun();
        projection.apply(new RunResult.RunFinished(empty, null, List.of(), List.of(), null, false));

        assertEquals(FactoryRunProjection.SUCCEEDED, projection.find(delivered).orElseThrow().status());
        assertEquals(FactoryRunProjection.DELIVERED_NOTHING, projection.find(empty).orElseThrow().status());
        assertNull(projection.find(empty).orElseThrow().pushedRef());
        // Not a failure: nothing to send an operator hunting for.
        assertNull(projection.find(empty).orElseThrow().failureCause());
    }

    @Test
    void aRunThatDeliveredWithoutFinishingGetsItsOwnStatus() {
        // Neither neighbour tells the truth. 'failed' hides a branch that really is on the remote,
        // and 'succeeded' asserts a clean delivery for an agent killed mid-thought — so a
        // half-written change looks finished and reviews like one. This is the third time this
        // schema has made the same call; 'push_gate_refused' and 'delivered_nothing' were both
        // folded into a neighbouring status once and could not be told apart afterwards.
        String runId = queuedRun();
        projection.apply(new RunResult.RunFinished(runId, "refs/heads/spire/x", List.of("a.txt"),
                List.of(), null, true));

        FactoryRunProjection.RunView view = projection.find(runId).orElseThrow();
        assertEquals(FactoryRunProjection.DELIVERED_UNFINISHED, view.status());
        assertEquals("refs/heads/spire/x", view.pushedRef(),
                "the ref is recorded either way: the work is on the remote and an operator must find it");
        assertNull(view.failureCause(), "nothing infrastructural broke, so nothing is classified");
    }

    /**
     * A run an operator stopped is not a run that broke.
     *
     * <p>The worker already separates recording the cancellation from acting on it, precisely so the
     * killed agent's non-zero exit is not read as a fault — and then this projection wrote
     * {@code failed} unconditionally and threw that away, telling whoever pressed the button that the
     * thing they stopped was broken. {@code 'cancelled'} has been in the V43 status set since M0 with
     * nothing writing it.
     *
     * <p>The status assertion is the discriminating one: the cause was already correct, so a test
     * checking only the cause passes both before and after the fix.
     */
    @Test
    void aCancelledRunIsNotReportedAsAFailure() {
        String runId = queuedRun();

        projection.apply(new RunResult.RunFailed(runId, "CANCELLED", "stopped by an operator", false, null));

        FactoryRunProjection.RunView view = projection.find(runId).orElseThrow();
        assertEquals(FactoryRunProjection.CANCELLED, view.status());
        assertEquals("CANCELLED", view.failureCause(),
                "the cause stays on the row, so it says both what happened and who ended it");
    }

    @Test
    void anOrdinaryFailureIsStillAFailure() {
        // The other half: the cancelled branch must not swallow everything that reaches failed().
        String runId = queuedRun();

        projection.apply(new RunResult.RunFailed(runId, "AGENT_FAILED", "exit 1", true, null));

        assertEquals(FactoryRunProjection.FAILED, projection.find(runId).orElseThrow().status());
    }

    /**
     * An unacknowledged dispatch is unresolved, not failed (FR-F10).
     *
     * <p>The distinction decides whether the run may be started again, so it is the whole point. A
     * failed dispatch is re-armable and a retry is free; an unacknowledged one may already be on the
     * topic, and re-arming it puts a second agent on the same branch with the model paid twice.
     */
    @Test
    void anUnacknowledgedDispatchIsUncertainRatherThanFailed() {
        String runId = queuedRun();

        projection.dispatchUncertain(runId, "TEST-no ack");

        FactoryRunProjection.RunView view = projection.find(runId).orElseThrow();
        assertEquals(FactoryRunProjection.DISPATCH_UNCERTAIN, view.status());
        assertEquals("DISPATCH_UNCERTAIN", view.failureCause());
        assertNull(column(runId, "ended_at").getFirst(),
                "unresolved is not ended: the V51 CHECK pairs the two, and a finish time here would"
                        + " assert the run is over while it may still be executing");
    }

    /**
     * The fail-closed rule, and the one an operator's retry runs into.
     *
     * <p>{@code queued} re-arms a row whose dispatch definitely failed. It must NOT re-arm an
     * uncertain one — that is the duplicate this state exists to prevent — so an identical retry is
     * refused rather than silently publishing a second command.
     */
    @Test
    void anUncertainDispatchIsNeverReArmedByARetry() {
        String runId = queuedRun();
        projection.dispatchUncertain(runId, "TEST-no ack");

        assertFalse(reQueue(runId), "a retry must be refused while the first command may be running");
        assertEquals(FactoryRunProjection.DISPATCH_UNCERTAIN,
                projection.find(runId).orElseThrow().status(), "and the row is left as it was");
    }

    /**
     * The half that keeps the rule above from being unconditional: a dispatch that definitely failed
     * IS re-armable, and always was. Without this, making `queued` refuse everything would pass.
     */
    @Test
    void aDispatchThatDefinitelyFailedIsStillReArmable() {
        String runId = queuedRun();
        projection.dispatchFailed(runId, "TEST-refused outright");

        assertTrue(reQueue(runId));
        assertEquals(FactoryRunProjection.QUEUED, projection.find(runId).orElseThrow().status());
    }

    /**
     * Reality resolves the uncertainty, and usually first.
     *
     * <p>If the record did land, that run's {@code RunStarted} is on its way — so the status has to
     * stay live. Excluding it would drop the result of a run that really is executing, which is
     * exactly the case the uncertainty is about, and would leave the row waiting for an operator to
     * decide something the run had already answered.
     */
    @Test
    void aRunThatStartsAfterAllResolvesItsOwnUncertainty() {
        String runId = queuedRun();
        projection.dispatchUncertain(runId, "TEST-no ack");

        projection.apply(new RunResult.RunStarted(runId, "unit-abc"));

        FactoryRunProjection.RunView view = projection.find(runId).orElseThrow();
        assertEquals(FactoryRunProjection.RUNNING, view.status());
        assertNull(view.failureCause(), "the uncertainty is gone, not merely overwritten alongside");
    }

    @Test
    void anOperatorCanResolveAnUncertainDispatchAsNeverStarted() {
        String runId = queuedRun();
        projection.dispatchUncertain(runId, "TEST-no ack");

        assertTrue(projection.resolveDispatch(runId, true, "TEST-operator says it never ran"));

        // The re-armable shape, so the operator's ordinary retry starts the run through the path
        // that already exists rather than through a second mechanism invented for this.
        FactoryRunProjection.RunView view = projection.find(runId).orElseThrow();
        assertEquals(FactoryRunProjection.FAILED, view.status());
        assertEquals(FactoryRunProjection.DISPATCH_FAILED, view.failureCause());
        assertTrue(reQueue(runId), "and it is now retryable");
    }

    @Test
    void anOperatorCanResolveAnUncertainDispatchAsStarted() {
        String runId = queuedRun();
        projection.dispatchUncertain(runId, "TEST-no ack");

        assertTrue(projection.resolveDispatch(runId, false, "TEST-operator says it did run"));

        FactoryRunProjection.RunView view = projection.find(runId).orElseThrow();
        assertEquals(FactoryRunProjection.FAILED, view.status());
        assertEquals("DISPATCH_UNCERTAIN", view.failureCause());
        assertFalse(reQueue(runId),
                "terminal, and deliberately not re-armable: publishing again for a run that really"
                        + " happened is the duplicate the whole state exists to prevent");
    }

    @Test
    void aRunThatResolvedItselfCannotBeResolvedByHand() {
        // An operator reading a stale page must not overwrite what the run itself has since said.
        String runId = queuedRun();
        projection.dispatchUncertain(runId, "TEST-no ack");
        projection.apply(new RunResult.RunStarted(runId, "unit-abc"));

        assertFalse(projection.resolveDispatch(runId, true, "TEST-too late"));
        assertEquals(FactoryRunProjection.RUNNING, projection.find(runId).orElseThrow().status());
    }

    /**
     * The guard {@code dispatchFailed} already has, for the same reason.
     *
     * <p>An acknowledgement can elapse after the record landed and a worker already started the run,
     * whose {@code RunStarted} has moved this row to running. Marking that row would put a live run
     * into a state an operator is asked to resolve, about a question reality has already settled.
     */
    @Test
    void aRunThatIsAlreadyRunningIsNeverMarkedUncertain() {
        String runId = queuedRun();
        projection.apply(new RunResult.RunStarted(runId, "unit-abc"));

        projection.dispatchUncertain(runId, "TEST-late ack timeout");

        assertEquals(FactoryRunProjection.RUNNING, projection.find(runId).orElseThrow().status());
    }

    @Test
    void aProducersOwnVocabularyIsStoredAsTheClosedSetsValue() {
        // The harness says PUSH_GATE_REFUSED and the publisher says PUSH_FAILED; V46 constrains the
        // column to the wire set. Translating on the way in is what lets both keep their own words
        // without the read model growing a third spelling for one thing.
        String harnessWord = queuedRun();
        projection.apply(new RunResult.RunFailed(harnessWord, "PUSH_GATE_REFUSED", "protected path", false, null));

        // PUSH_FAILED is the publisher's OLD ambiguous word, and it aliases to the transport
        // reading rather than to a refusal: told a network fault is the forge answering no, a blip
        // is never retried. "remote hung up" is exactly that case.
        String publisherWord = queuedRun();
        projection.apply(new RunResult.RunFailed(publisherWord, "PUSH_FAILED", "remote hung up", true, null));

        assertEquals(List.of("GATE_REFUSED"), column(harnessWord, "failure_cause"));
        assertEquals(List.of("PUSH_TRANSPORT_FAILED"), column(publisherWord, "failure_cause"));
    }

    @Test
    void anUnrecognisedCauseIsRecordedAsUnclassifiedWithItsOwnWordKept() {
        // The column's CHECK would reject an unknown string, and that rejection would throw inside
        // the result handler of a run that has already been paid for. It lands as UNCLASSIFIED
        // instead, with the producer's spelling carried in the detail so it is still diagnosable.
        String runId = queuedRun();

        projection.apply(new RunResult.RunFailed(runId, "SOMETHING_NEW", "a newer worker said this", false, null));

        assertEquals(List.of("UNCLASSIFIED"), column(runId, "failure_cause"));
        assertTrue(column(runId, "failure_detail").getFirst().contains("SOMETHING_NEW"),
                "the unrecognised cause must survive in the detail, or it is lost entirely");
        assertTrue(column(runId, "failure_detail").getFirst().contains("a newer worker said this"),
                "the producer's own detail must not be discarded by the prefix");
    }

    private String queuedRun() {
        String runId = "run::github:TEST-acme/app:subject-" + UUID.randomUUID() + ":1";
        assertTrue(reQueue(runId));
        return runId;
    }

    /**
     * The operator's retry: byte-identical parameters, which is what the re-arm requires.
     *
     * <p>Returns what {@code queued} answers, so a test can assert whether the row was re-armed
     * rather than inferring it from the status afterwards -- the two differ for a row the
     * ON CONFLICT matched and the WHERE declined to touch.
     */
    private boolean reQueue(String runId) {
        return projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "gpt-5.6", "main",
                "abc1234", "spire/x", "spire-bot"));
    }

    @Test
    void aRunIsQueuedThenRunningThenSucceeded() {
        String runId = queuedRun();
        assertEquals(FactoryRunProjection.QUEUED, projection.find(runId).orElseThrow().status());

        projection.apply(new RunResult.RunStarted(runId, "container-1"));
        assertEquals(FactoryRunProjection.RUNNING, projection.find(runId).orElseThrow().status());

        projection.apply(new RunResult.RunFinished(runId, "refs/heads/spire/x",
                List.of("src/Foo.java"), List.of(), Map.of("INPUT", 12L), false));

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
                List.of(".github/workflows/ci.yml"), null, false));

        FactoryRunProjection.RunView view = projection.find(runId).orElseThrow();
        assertEquals(FactoryRunProjection.PUSH_GATE_REFUSED, view.status());
        assertEquals(List.of(".github/workflows/ci.yml"), view.blockedPaths());
        assertNull(view.pushedRef());
        assertNull(view.failureCause());
    }

    @Test
    void aFailureNamesItsCause() {
        String runId = queuedRun();

        projection.apply(new RunResult.RunFailed(runId, "SANDBOX_UNREACHABLE", "daemon down", true, null));

        FactoryRunProjection.RunView view = projection.find(runId).orElseThrow();
        assertEquals(FactoryRunProjection.FAILED, view.status());
        // The worker emits the harness's word; V46 stores the closed set's value for it.
        assertEquals("SANDBOX_LOST", view.failureCause());
    }

    @Test
    void aRedeliveredResultChangesNothing() {
        // The results channel acks on receipt, so the same RunFinished can arrive twice. The second
        // touches no row — and a redelivered RunStarted after the terminal result must not drag the
        // row back to running.
        String runId = queuedRun();
        RunResult.RunFinished finished = new RunResult.RunFinished(runId, "refs/heads/spire/x",
                List.of(), List.of(), null, false);

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
        projection.apply(new RunResult.RunFinished(finished, "refs/heads/spire/x", List.of(), List.of(), null, false));
        projection.queued(new FactoryRunProjection.QueuedRun(finished, "codex", "gpt-5.6", "main", "abc1234", "spire/x", "spire-bot"));
        assertEquals(FactoryRunProjection.SUCCEEDED, projection.find(finished).orElseThrow().status());

        String crashed = queuedRun();
        projection.apply(new RunResult.RunFailed(crashed, "SANDBOX_UNREACHABLE", "daemon down", true, null));
        projection.queued(new FactoryRunProjection.QueuedRun(crashed, "codex", "gpt-5.6", "main", "abc1234", "spire/x", "spire-bot"));
        assertEquals("SANDBOX_LOST", projection.find(crashed).orElseThrow().failureCause());

        String unacked = queuedRun();
        projection.dispatchFailed(unacked, "No broker ack within 10s");
        assertEquals(FactoryRunProjection.FAILED, projection.find(unacked).orElseThrow().status());
        projection.queued(new FactoryRunProjection.QueuedRun(unacked, "codex", "gpt-5.6", "main", "abc1234", "spire/x", "spire-bot"));
        FactoryRunProjection.RunView view = projection.find(unacked).orElseThrow();
        assertEquals(FactoryRunProjection.QUEUED, view.status());
        assertNull(view.failureCause());
    }

    @Test
    void aRetryWithDifferentParametersDoesNotReArmADispatchFailure() {
        // "Never acknowledged" is not "never sent": the first command may have landed and be the one
        // running. A re-arm that took the retry's parameters (an earlier fix did exactly that) would
        // leave that first run's result on a row describing another commit, model or account. The
        // differing retry matches no row and the caller refuses it; the row keeps its own facts.
        String runId = queuedRun();
        projection.dispatchFailed(runId, "No broker ack within 10s");

        boolean reArmed = projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "gpt-5.7-mini",
                "release/2", "fedcba9876543210fedcba9876543210fedcba98", "spire/x", "other-bot"));

        assertFalse(reArmed);
        assertEquals(List.of(FactoryRunProjection.FAILED, FactoryRunProjection.DISPATCH_FAILED, "gpt-5.6",
                "main", "abc1234", "spire-bot"),
                column(runId, "status", "failure_cause", "model", "base_branch", "base_commit", "pushed_as"));
    }

    @Test
    void theIdenticalRequestReArmsADispatchFailure() {
        String runId = queuedRun();
        projection.dispatchFailed(runId, "No broker ack within 10s");

        boolean reArmed = projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "gpt-5.6", "main",
                "abc1234", "spire/x", "spire-bot"));

        assertTrue(reArmed);
        assertEquals(java.util.Arrays.asList(FactoryRunProjection.QUEUED, null), column(runId, "status", "failure_cause"));
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
        projection.apply(new RunResult.RunFinished(runId, "refs/heads/spire/x", List.of(), List.of(), null, false));
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
        projection.apply(new RunResult.RunFinished(finished, "refs/heads/spire/x", List.of("a"), List.of(), null, false));
        view = projection.find(finished).orElseThrow();
        assertEquals(FactoryRunProjection.SUCCEEDED, view.status());
        assertNull(view.failureCause());

        // But a row that failed for any OTHER cause stays failed: that failure is the run's real end.
        String crashed = queuedRun();
        projection.apply(new RunResult.RunFailed(crashed, "SANDBOX_UNREACHABLE", "daemon down", true, null));
        projection.apply(new RunResult.RunStarted(crashed, "container-1"));
        assertEquals(FactoryRunProjection.FAILED, projection.find(crashed).orElseThrow().status());
    }

    @Test
    void anUnknownRunIsAbsentNotAnError() {
        assertTrue(projection.find("run::github:TEST-none/x:y:1").isEmpty());
    }

    @Test
    void theModelARunWasDispatchedWithIsReadableForItsCharge() {
        // The query itself, against a real row. Every other test of the charge path stubs this out,
        // so the SQL was executed by nothing -- and it is the input that decides whether a run's
        // spend can be priced at all.
        String runId = queuedRun();

        assertEquals(Optional.of("gpt-5.6"), projection.modelOf(runId),
                "the model queuedRun() dispatched with, read back through the production query");
    }

    @Test
    void aRunWithNoRowHasNoModelRatherThanAWrongOne() {
        // The charge path answers UNRECORDED here, which prices as UNKNOWN rather than free. An
        // empty Optional is what makes that reachable; a blank string would price as a catalogued
        // model nobody registered.
        assertEquals(Optional.empty(),
                projection.modelOf("run::github:TEST-acme/app:never-queued-" + UUID.randomUUID() + ":1"));
    }

    @Test
    void aStartedRunRecordsTheSandboxAnOperatorHasToFind() {
        // The other half of the RunStarted fix. The wire carried a corrected unit id and the
        // projection discarded it, so the container label remained the only route to a preserved
        // sandbox -- exactly the workaround the debt entry was closed for removing.
        String runId = queuedRun();

        projection.apply(new RunResult.RunStarted(runId, "container-abc123"));

        assertEquals("container-abc123", projection.find(runId).orElseThrow().unitId());
    }

    @Test
    void aRunThatNeverStartedNamesNoSandbox() {
        // Null is the honest answer for a queued run: the worker has not created anything yet, so
        // there is nothing to point at.
        assertNull(projection.find(queuedRun()).orElseThrow().unitId());
    }
}
