package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review lane (harness reviews, step 4), with the claim store, the launcher and the broker faked by
 * {@link RunDispatcherTest}'s fakes: the build lane's claim, ack and cancel rules, on review runs only.
 */
class ReviewRunDispatcherTest {

    private static final String RUN_ID = "run::github:TEST-work/app:review-7-aaaaaaaaaaaa-0123abcd:1";

    static final RunCommand.ExecuteReviewRun REVIEW = new RunCommand.ExecuteReviewRun(RUN_ID,
            new RunCommand.ExecuteRun(RUN_ID, new RepoRef("TEST-work", "app"), "https://forge.example.test/TEST-work/app.git",
                    "TEST-source", "a".repeat(40), "TEST-source", "TEST-review the pull request", "codex", "TEST-model",
                    "TEST-image", List.of(), 60, "enc-scm", "enc-harness"),
            new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot"));

    private final List<String> order = new ArrayList<>();
    private final RunDispatcherTest.FakeClaims claims = new RunDispatcherTest.FakeClaims(order);
    private final RunDispatcherTest.FakeLauncher launcher = new RunDispatcherTest.FakeLauncher(order);
    private final RunDispatcherTest.FakeEmitter results = new RunDispatcherTest.FakeEmitter();
    private final RunDispatcherTest.FakeLeases leases = new RunDispatcherTest.FakeLeases(order);
    private final RunRegistry registry = new RunRegistry();
    private final ReviewRunDispatcher dispatcher = dispatcher();

    private ReviewRunDispatcher dispatcher() {
        ReviewRunDispatcher d = new ReviewRunDispatcher();
        d.claims = claims;
        d.runtime = new RunDispatcherTest.FakeRuntime();
        d.launcher = launcher;
        d.results = results;
        d.leases = leases;
        d.registry = registry;
        d.failures = RunLauncherTest.failuresWith(new Credentials() {
            @Override
            public Scm scm(String runId, String packed) {
                return new Scm(RunLauncherTest.SCM_USERNAME, RunLauncherTest.READ_SECRET,
                        RunLauncherTest.SCM_USERNAME, RunLauncherTest.WRITE_SECRET);
            }

            @Override
            public Map<String, String> harnessEnv(String runId, String packed) {
                return Map.of("OPENAI_API_KEY", RunLauncherTest.MODEL_KEY);
            }
        });
        return d;
    }

    private RunDispatcherTest.Delivery deliver(RunCommand command) {
        RunDispatcherTest.Delivery delivery = new RunDispatcherTest.Delivery(order);
        dispatcher.onCommand(delivery.of(command)).toCompletableFuture().join();
        return delivery;
    }

    @Test
    void aReviewRunIsClaimedThenAckedThenLaunchedReadOnly() {
        RunDispatcherTest.Delivery delivery = deliver(REVIEW);

        assertTrue(delivery.acked);
        assertEquals(List.of("claim", "ack", "read-cancel", "lease", "launch-review", "unit", "read-cancel", "release"),
                order, "the build lane's order: claim, ack, cancel read, lease, unit, cancel read again");
        assertEquals(List.of("RunStarted", "RunReviewArtifact"),
                results.sent.stream().map(r -> r.getClass().getSimpleName()).toList());
    }

    /** Review Focus 1: a worker that crashed before its ack is redelivered the command, and must not spend twice. */
    @Test
    void aRedeliveredReviewRunStartsNoSecondUnit() {
        deliver(REVIEW);
        RunDispatcherTest.Delivery again = deliver(REVIEW);

        assertEquals(1, launcher.reviewLaunches, "a second unit would spend a seat's window twice");
        assertTrue(again.acked, "the redelivery is consumed, not left to be redelivered forever");
        assertEquals(2, results.sent.size(), "nothing is re-emitted for a redelivery");
    }

    /** Review Focus 2: a cancel recorded while the record was still on the topic stops it from ever starting. */
    @Test
    void aReviewRunCancelledBeforeItStartsNeverStarts() {
        claims.taken.add(RUN_ID + "/" + RunDispatcher.CANCEL_SLOT);

        RunDispatcherTest.Delivery delivery = deliver(REVIEW);

        assertEquals(0, launcher.reviewLaunches, "no agent may run for a cancelled review");
        assertFalse(order.contains("lease"), "and no lease either: " + order);
        assertTrue(delivery.acked);
        RunResult.RunFailed reported = assertInstanceOf(RunResult.RunFailed.class, results.sent.getLast());
        assertEquals(RunFailureCause.CANCELLED.name(), reported.cause());
    }

    /** Review Focus 2, the other half: a cancel that reaches a running review stops it and is reported as one. */
    @Test
    void aReviewRunCancelledWhileItRunsIsReportedCancelled() {
        launcher.reviewResult = new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "exit 137", false,
                Map.of("INPUT", 1200L), List.of());
        launcher.onLaunch = () -> registry.cancel(RUN_ID);

        deliver(REVIEW);

        RunResult.RunFailed reported = assertInstanceOf(RunResult.RunFailed.class, results.sent.getLast());
        assertEquals(RunFailureCause.CANCELLED.name(), reported.cause());
        assertEquals(1200L, reported.tokenUsage().get("INPUT"), "the tokens it bought are still charged");
        assertEquals(0, registry.size(), "a finished review is not live work");
    }

    @Test
    void theReviewLaneOnlyRunsReviewRuns() {
        RunCommand.ExecuteRun build = REVIEW.execution();

        RunDispatcherTest.Delivery delivery = deliver(build);

        assertTrue(delivery.acked, "a build read here is settled, never redelivered onto this lane");
        assertNull(delivery.nacked);
        assertEquals(0, launcher.launches + launcher.readOnlyLaunches + launcher.reviewLaunches);
        assertTrue(claims.taken.isEmpty(), "its execute slot stays free for the build lane: " + claims.taken);
        assertTrue(results.sent.isEmpty());
    }

    /** A review's answer is what the lane reports, so the orchestrator charges and records it once. */
    @Test
    void theReviewAnswerIsReported() {
        deliver(REVIEW);

        RunReviewArtifact answer = assertInstanceOf(RunReviewArtifact.class, results.sent.getLast());
        assertEquals(REVIEW.review(), answer.review());
    }
}
