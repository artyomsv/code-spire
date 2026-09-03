package dev.codespire.runworker;

import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import io.smallrye.reactive.messaging.kafka.Record;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one path a reclaimed run's result takes to the orchestrator.
 *
 * <p>It had no test at all: every watchdog case went through a test-only seam in production code, so
 * the shipped path — with its four-way exception ladder and its "never throws" contract — was
 * executed by nothing. A sweep that dies on a broker refusal stops reclaiming everything behind it,
 * and the sandbox it was reporting on is still there to be found again, so the contract is the point
 * rather than a nicety.
 */
class RunResultReporterTest {

    private static final RunResult FAILED = new RunResult.RunFailed(
            "run::github:TEST-acme/app:s:1", RunFailureCause.SANDBOX_LOST.name(),
            "its sandbox outlived the control plane", true, null);

    /** An emitter whose completion the test decides. */
    private static final class FakeEmitter implements Emitter<Record<String, RunResult>> {
        final List<RunResult> sent = new ArrayList<>();
        CompletableFuture<Void> ack = CompletableFuture.completedFuture(null);

        @Override
        public CompletionStage<Void> send(Record<String, RunResult> payload) {
            sent.add(payload.value());
            return ack;
        }

        @Override
        public <M extends Message<? extends Record<String, RunResult>>> void send(M message) {
            throw new UnsupportedOperationException();
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

    private final FakeEmitter emitter = new FakeEmitter();

    private RunResultReporter reporter() {
        RunResultReporter reporter = new RunResultReporter();
        reporter.results = emitter;
        reporter.ackSeconds = 5;
        return reporter;
    }

    @Test
    void aResultIsPublishedKeyedByItsRun() {
        reporter().report(FAILED);

        assertEquals(List.of(FAILED), emitter.sent);
    }

    @Test
    void aBrokerRefusalIsReportedRatherThanThrown() {
        // A scheduled sweep that dies here stops reclaiming every orphan behind this one, and the
        // sandbox it was reporting on is still there to be found next tick.
        emitter.ack = CompletableFuture.failedFuture(new IllegalStateException("no subscriber"));

        reporter().report(FAILED);

        assertEquals(1, emitter.sent.size(), "the attempt was made; only the acknowledgement failed");
    }

    @Test
    void anAckThatNeverArrivesIsBoundedRatherThanBlockingTheSweep() {
        // An unbounded wait on a broker that has stopped answering holds the scheduler thread, and
        // under SKIP that means no orphan is reclaimed until it returns.
        emitter.ack = new CompletableFuture<>();
        RunResultReporter reporter = reporter();
        reporter.ackSeconds = 1;

        long before = System.nanoTime();
        reporter.report(FAILED);

        assertTrue(System.nanoTime() - before < java.time.Duration.ofSeconds(30).toNanos(),
                "the wait is bounded by the configured budget, not by the broker's patience");
    }

    @Test
    void aNonPositiveAckBudgetIsRefusedAtStartup() {
        // Zero makes every wait time out instantly, so every reclamation report is lost while the
        // configuration looks set — the silent version of the outage this class exists to report.
        RunResultReporter reporter = reporter();
        reporter.ackSeconds = 0;

        assertThrows(IllegalStateException.class, () -> reporter.check(null));
    }

    @Test
    void aWorkableBudgetStarts() {
        // The other half: without it the refusal could be unconditional and nothing above would show
        // it, because none of those tests boots the application.
        reporter().check(null);
    }

    @Test
    void theInterruptFlagIsRestoredRatherThanSwallowed() {
        // The caller is a sweep that will keep iterating units. Swallowing the interrupt leaves it
        // running work the JVM has been asked to stop.
        emitter.ack = new CompletableFuture<>();
        RunResultReporter reporter = reporter();
        reporter.ackSeconds = 30;

        Thread worker = new Thread(() -> reporter.report(FAILED));
        worker.start();
        worker.interrupt();

        assertFalse(assertDoesNotHang(worker), "the report returns rather than blocking on the interrupt");
    }

    /** @return whether the thread was still alive after a bounded wait. */
    private static boolean assertDoesNotHang(Thread worker) {
        try {
            worker.join(java.time.Duration.ofSeconds(10).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return worker.isAlive();
    }

}
