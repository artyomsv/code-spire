package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.scm.RepoRef;
import io.smallrye.reactive.messaging.kafka.Record;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dispatcher's wiring, with the claim store, the launcher and the broker faked.
 *
 * <p>Written after a review found this class had no test at all — and the class had just shipped a
 * compile break to a shared tree. Its contract is order: claim, then ack, then run; and a
 * redelivery is dropped by the claim and never by the launcher. Each of those is a line that could
 * be swapped or deleted with every other test still green, so each has a test here that fails when
 * it is.
 */
class RunDispatcherTest {

    private static final RunCommand.ExecuteRun EXECUTE = new RunCommand.ExecuteRun(
            "run::github:acme/app:finding-1:1", new RepoRef("acme", "app"),
            "https://github.com/acme/app.git", "main", "abc1234", "spire/finding-1",
            "fix the typo", "codex", "gpt-5.6", "img", List.of(), 60, "enc-scm", "enc-harness");

    private static final RunResult.RunFinished FINISHED = new RunResult.RunFinished(
            EXECUTE.runId(), "refs/heads/spire/finding-1", List.of("a.txt"), List.of(), null);

    /** In-memory claims: the first claim of a slot wins, exactly as the unique key does. */
    static final class FakeClaims extends RunClaimStore {
        final Set<String> taken = new HashSet<>();
        final List<String> order;

        FakeClaims(List<String> order) {
            this.order = order;
        }

        @Override
        public boolean claim(String runId, String slot) {
            order.add("claim");
            return taken.add(runId + "/" + slot);
        }
    }

    static final class FakeLauncher extends RunLauncher {
        final List<String> order;
        int launches;
        RuntimeException failWith;

        FakeLauncher(List<String> order) {
            this.order = order;
        }

        // The two-argument form, because that is what the dispatcher calls. Overriding the
        // convenience overload instead compiles, runs the REAL launcher, and every assertion about
        // ordering and idempotency then measures nothing.
        @Override
        public RunResult launch(RunCommand.ExecuteRun command,
                                java.util.function.Consumer<dev.codespire.contract.event.RunEventRecord> stream) {
            order.add("launch");
            launches++;
            if (failWith != null) {
                throw failWith;
            }
            return FINISHED;
        }
    }

    static final class FakeEmitter implements Emitter<Record<String, RunResult>> {
        final List<RunResult> sent = new ArrayList<>();
        /** The broker refusing the full result — a record too large — while accepting a compact one. */
        boolean refuseFinished;
        /** The broker refusing everything: the run must still not be dead-lettered. */
        boolean refuseAll;

        @Override
        public CompletionStage<Void> send(Record<String, RunResult> record) {
            if (refuseAll || refuseFinished && record.value() instanceof RunResult.RunFinished) {
                return CompletableFuture.failedFuture(new IllegalStateException("record too large"));
            }
            sent.add(record.value());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <M extends Message<? extends Record<String, RunResult>>> void send(M message) {
            sent.add(message.getPayload().value());
        }

        @Override
        public void complete() {
        }

        @Override
        public void error(Exception e) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean hasRequests() {
            return true;
        }
    }

    /** A message that records whether, and in which order, it was acked or nacked. */
    static final class Delivery {
        final List<String> order;
        boolean acked;
        Throwable nacked;

        Delivery(List<String> order) {
            this.order = order;
        }

        Message<RunCommand> of(RunCommand payload) {
            return Message.of(payload,
                    () -> {
                        order.add("ack");
                        acked = true;
                        return CompletableFuture.completedFuture(null);
                    },
                    reason -> {
                        order.add("nack");
                        nacked = reason;
                        return CompletableFuture.completedFuture(null);
                    });
        }
    }

    private final List<String> order = new ArrayList<>();
    private final FakeClaims claims = new FakeClaims(order);
    private final FakeLauncher launcher = new FakeLauncher(order);
    private final FakeEmitter results = new FakeEmitter();
    private final RunDispatcher dispatcher = dispatcher();

    private RunDispatcher dispatcher() {
        RunDispatcher d = new RunDispatcher();
        d.claims = claims;
        d.launcher = launcher;
        d.results = results;
        // The real collaborator with a faked credential source, so the dispatcher's own failures
        // get the same scrub and the same retry answer the launcher's do. Leaving it unwired is
        // what the review found: this class built details the launcher's rules never touched.
        d.failures = RunLauncherTest.failuresWith(new Credentials() {
            @Override
            public Scm scm(String runId, String packed) {
                return new Scm(RunLauncherTest.SCM_USERNAME, RunLauncherTest.READ_SECRET,
                        RunLauncherTest.SCM_USERNAME, RunLauncherTest.WRITE_SECRET);
            }

            @Override
            public java.util.Map<String, String> harnessEnv(String runId, String packed) {
                return java.util.Map.of("OPENAI_API_KEY", RunLauncherTest.MODEL_KEY);
            }
        });
        return d;
    }

    @Test
    void claimsThenAcksThenRuns() {
        Delivery delivery = new Delivery(order);
        dispatcher.onCommand(delivery.of(EXECUTE)).toCompletableFuture().join();

        assertEquals(List.of("claim", "ack", "launch"), order,
                "the claim goes first (a crash between them must not lose the command), the ack "
                        + "before the run (an hour-long run must not age the record), the run last");
        assertTrue(delivery.acked);
        assertEquals(List.of("RunStarted", "RunFinished"),
                results.sent.stream().map(r -> r.getClass().getSimpleName()).toList());
    }

    @Test
    void aRedeliveryIsAckedAndNeverRunAgain() {
        dispatcher.onCommand(new Delivery(order).of(EXECUTE)).toCompletableFuture().join();
        Delivery again = new Delivery(order);
        dispatcher.onCommand(again.of(EXECUTE)).toCompletableFuture().join();

        assertEquals(1, launcher.launches, "a second unit would spend money twice");
        assertTrue(again.acked, "the redelivery is consumed, not left to be redelivered forever");
        assertEquals(2, results.sent.size(), "nothing is re-emitted for a redelivery");
    }

    @Test
    void anUnexpectedLauncherFailureStillReportsATerminalResult() {
        // A run that reports nothing is indistinguishable from one still working.
        // The catch-all: the exception nobody has reviewed, by definition. Its message therefore
        // gets the same scrub as every other failure detail, which it did not before.
        launcher.failWith = new IllegalStateException(
                "daemon vanished: env=[OPENAI_API_KEY=" + RunLauncherTest.MODEL_KEY + "]");
        dispatcher.onCommand(new Delivery(order).of(EXECUTE)).toCompletableFuture().join();

        RunResult last = results.sent.getLast();
        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, last);
        assertEquals("WORKER_FAILED", failed.cause());
        assertTrue(failed.detail().contains("daemon vanished"), "the diagnosis must survive");
        assertFalse(failed.detail().contains(RunLauncherTest.MODEL_KEY),
                "a credential reached failure_detail through the dispatcher's catch-all");
        assertTrue(failed.retryable(), "and the answer comes from the cause, not from this call site");
    }

    @Test
    void aResultTheBrokerRefusesIsReplacedByACompactOneAndNeverDeadLettersTheCommand() {
        // The command is acked and claimed before the run; a throw after that could only dead-letter
        // a record that has already run — a phantom the operator is invited to replay into a claim
        // that drops it. The broker refusing the full result gets a compact RunFailed instead.
        results.refuseFinished = true;
        Delivery delivery = new Delivery(order);

        dispatcher.onCommand(delivery.of(EXECUTE)).toCompletableFuture().join();

        assertTrue(delivery.acked);
        assertEquals(null, delivery.nacked, "nothing to nack: the record was already settled");
        RunResult.RunFailed compact = assertInstanceOf(RunResult.RunFailed.class, results.sent.getLast());
        assertEquals("RESULT_UNPUBLISHABLE", compact.cause());
        assertFalse(compact.retryable(), "the run happened; re-running it would spend twice");
    }

    @Test
    void aBrokerThatRefusesEverythingStillNeverDeadLettersTheCommand() {
        // Both the full result and the compact one refused: the loss is logged and the handler
        // returns. A throw here reached SmallRye after the manual ack and dead-lettered a record
        // that had already run.
        results.refuseAll = true;
        Delivery delivery = new Delivery(order);

        dispatcher.onCommand(delivery.of(EXECUTE)).toCompletableFuture().join();

        assertTrue(delivery.acked);
        assertEquals(null, delivery.nacked);
        assertEquals(1, launcher.launches);
        assertTrue(results.sent.isEmpty(), "nothing was accepted, and nothing was thrown");
    }

    @Test
    void aPoisonRecordIsNackedNotAckedAndNotRun() {
        Delivery delivery = new Delivery(order);
        dispatcher.onCommand(delivery.of(null)).toCompletableFuture().join();

        assertFalse(delivery.acked);
        assertInstanceOf(IllegalArgumentException.class, delivery.nacked);
        assertEquals(0, launcher.launches);
        assertTrue(results.sent.isEmpty());
    }

    @Test
    void aCancelIsAcknowledgedWithoutAClaimOrARun() {
        Delivery delivery = new Delivery(order);
        dispatcher.onCommand(delivery.of(new RunCommand.CancelRun(EXECUTE.runId(), "operator"))).toCompletableFuture().join();

        assertTrue(delivery.acked);
        assertNull(delivery.nacked);
        assertEquals(0, launcher.launches);
        assertTrue(claims.taken.isEmpty(), "a cancel must not take the execute slot from a later ExecuteRun");
    }
}
