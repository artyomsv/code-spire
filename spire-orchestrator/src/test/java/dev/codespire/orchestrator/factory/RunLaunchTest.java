package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.pipeline.BrokerAckFailure;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What became of a run command handed to the broker, and what the row is left saying.
 *
 * <p><b>This behaviour was inline in {@code RunResource} and is tested there through HTTP.</b> It is
 * extracted because {@code /fix} is about to need the same three outcomes, and the failure this
 * class exists to prevent is two readings of "did the record land?" — the shape that produced two
 * credential scrubbers here whose rules diverged. Those REST tests still pass unchanged and are the
 * proof the extraction changed nothing; these are the direct cover the new class earns on its own.
 *
 * <p>A plain unit test, no Quarkus: the classification is a pure decision over an exception, and the
 * two collaborators are the only things that touch a database. Every method those fakes carry that
 * this path does NOT reach throws, because an un-overridden one opens a real {@code DataSource} from
 * a unit test — the trap this project has now hit ten times.
 */
class RunLaunchTest {

    private static final String RUN_ID = "run::github:acme/app:subject:1";

    private final List<String> failed = new ArrayList<>();
    private final List<String> uncertain = new ArrayList<>();
    private final List<String> published = new ArrayList<>();

    /** What the emitter does when asked. Null means it succeeds. */
    private IllegalStateException publishFault;

    private RunLaunch launch() {
        RunLaunch launch = new RunLaunch();
        launch.emitter = new RunCommandEmitter() {
            @Override
            public void dispatch(RunCommand command) {
                published.add(command.runId());
                if (publishFault != null) {
                    throw publishFault;
                }
            }

            // Not on this path, and the neighbouring method on the same class — which is exactly the
            // kind that gets called by accident during a later edit and reaches a real emitter.
            @Override
            public void control(RunCommand command) {
                throw new AssertionError("a dispatch must not travel on the control topic");
            }
        };
        launch.projection = new FactoryRunProjection() {
            @Override
            public void dispatchFailed(String runId, String detail) {
                failed.add(runId + "|" + detail);
            }

            @Override
            public void dispatchUncertain(String runId, String detail) {
                uncertain.add(runId + "|" + detail);
            }

            // The row is the CALLER's write, deliberately, so that a run can never be on the bus
            // without one. If this class ever reaches it, that ordering has been lost.
            @Override
            public boolean queued(QueuedRun row) {
                throw new AssertionError("the row is written by the caller, before the launch");
            }
        };
        return launch;
    }

    private static RunCommand.ExecuteRun command() {
        return new RunCommand.ExecuteRun(RUN_ID, new RepoRef("acme", "app"),
                "https://github.com/acme/app.git", "main", "cafe1234", "spire/x", "do the thing",
                "codex", "gpt-x", "img", List.of(), 900,
                "TEST-scm-credential", "TEST-harness-credential");
    }

    @Test
    void anAcknowledgedCommandLeavesTheRowAlone() {
        RunLaunch.Outcome outcome = launch().launch(command());

        assertInstanceOf(RunLaunch.Dispatched.class, outcome);
        assertEquals(List.of(RUN_ID), published);
        assertTrue(failed.isEmpty(), failed.toString());
        assertTrue(uncertain.isEmpty(), uncertain.toString());
    }

    /**
     * A record the broker refused outright never reached a partition, so the run definitely did not
     * start — the one outcome an identical retry may safely re-arm.
     */
    @Test
    void aRefusedRecordIsADefiniteMissAndSaysSoOnTheRow() {
        publishFault = BrokerAckFailure.rejected("run command ExecuteRun",
                new RecordTooLargeException("too big"));

        RunLaunch.Outcome outcome = launch().launch(command());

        assertInstanceOf(RunLaunch.DefiniteMiss.class, outcome);
        assertTrue(outcome.isReArmable(), "a record that never left IS safe to send again");
        assertEquals(List.of(RUN_ID + "|" + RunLaunch.DISPATCH_FAILED_DETAIL), failed);
        assertTrue(uncertain.isEmpty(), uncertain.toString());
    }

    /**
     * <b>A fault nobody could classify is AMBIGUOUS, not a miss.</b>
     *
     * <p>This is the direction the whole class turns on. A terminated channel or a full emitter
     * buffer throws before a record is offered to the producer at all, and reading that as "it
     * definitely did not land" is how an identical retry puts a second agent on the same branch and
     * pays for the model twice. A fault we cannot read tells us nothing about whether the record
     * left, so it goes to the outcome that forbids the automatic retry.
     */
    @Test
    void anUnclassifiedPublishFaultIsUncertainRatherThanAMiss() {
        publishFault = new IllegalStateException("emitter buffer is full");

        RunLaunch.Outcome outcome = launch().launch(command());

        assertInstanceOf(RunLaunch.Uncertain.class, outcome);
        assertFalse(outcome.isReArmable(), "an unread fault must never authorise a second command");
        assertEquals(List.of(RUN_ID + "|" + RunLaunch.uncertainDetail(RUN_ID)), uncertain);
        assertTrue(failed.isEmpty(), failed.toString());
    }

    /**
     * An ack failure that MAY have landed goes the same way as an unreadable one.
     *
     * <p>Distinct from the case above because it arrives as a {@code BrokerAckFailure} — the type
     * the classification tests — so it is the case that proves the check reads
     * {@code mayHaveLanded()} rather than merely the exception's class.
     */
    @Test
    void anAckFailureThatMayHaveLandedIsUncertainToo() {
        publishFault = BrokerAckFailure.notAcknowledged("run command ExecuteRun was not acknowledged",
                new IllegalStateException("no ack within the budget"));

        assertInstanceOf(RunLaunch.Uncertain.class, launch().launch(command()));
        assertTrue(failed.isEmpty(), failed.toString());
    }

    /**
     * The durable detail names the endpoint that resolves it, spelled once.
     *
     * <p>Four messages describe this condition and only this one survives a page reload, so an
     * address that drifts from the real route makes the durable one the least useful of the four.
     */
    @Test
    void theUncertainRowCarriesTheAddressThatResolvesIt() {
        assertTrue(RunLaunch.uncertainDetail(RUN_ID).contains(RunLaunch.resolutionPath(RUN_ID)),
                RunLaunch.uncertainDetail(RUN_ID));
        assertEquals("/api/runs/" + RUN_ID + "/dispatch-resolution", RunLaunch.resolutionPath(RUN_ID));
        // And it must NOT tell an operator to retry: the record may be on the topic already.
        assertFalse(RunLaunch.uncertainDetail(RUN_ID).contains("retry the same request"),
                "that is the definite-miss instruction, and here it is the expensive one");
    }
}
