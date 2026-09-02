package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunEventRecord;
import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.RunEventSummary;
import dev.codespire.harness.TerminalOutcome;
import dev.codespire.harness.UsageReport;
import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.LogChannel;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import dev.codespire.runtime.RunUnitSpec;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Creates the run unit and reads its two log streams.
 *
 * <p>Performs no git and holds no filesystem — that is the whole point of ADR-039, and it is what
 * lets any replica salvage any run rather than only the one that started it.
 */
@ApplicationScoped
public class RunLauncher {

    private static final Logger LOG = Logger.getLogger(RunLauncher.class);

    /** How long a failed salvage waits for the log readers to deliver what they already hold. */
    private static final Duration READER_GRACE = Duration.ofSeconds(5);

    private final ExecutorService streams = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("run-stream-", 0).factory());

    @PreDestroy
    void stopStreams() {
        streams.shutdownNow();
    }

    @Inject
    RunRuntime runtime;

    @Inject
    RunUnitBuilder builder;

    @Inject
    HarnessRegistry harnesses;

    @Inject
    RunFailures failures;

    /** Everything a run left behind for {@link #interpret}: the events, the publisher's report, the exit. */
    private record Observed(RunEventFold seen, PublisherOutcome outcome, Finalization finalization) {
    }

    /**
     * Run a command to a terminal result.
     *
     * <p>{@code observer} is told the instant the unit EXISTS — not before anything runs in it,
     * since the arm starts the containers inside {@code create}. Two callers need that moment and
     * neither could see it before: {@code RunStarted} was emitted ahead of creation and so passed
     * the run id in place of a unit id that did not exist yet, and the lease had nothing to record.
     *
     * <p>The observer is also told when the unit is GONE, and only then. The caller used to infer
     * that from the wire result, and a review found four paths where the inference was wrong in
     * the leaking direction. Silence therefore means "still there", which is the safe default.
     */
    public RunResult launch(RunCommand.ExecuteRun command, RunObserver observer) {
        HarnessAdapter adapter;
        RunUnitSpec unit;
        try {
            adapter = harnesses.forName(command.harness());
            unit = builder.build(command, adapter);
        } catch (RuntimeException e) {
            // Nothing was created, so nothing needs salvaging. Not retryable: the same command
            // would be rejected identically, and retrying a malformed dispatch is a loop.
            return failure(command, "BAD_COMMAND", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        RunHandle handle;
        try {
            handle = runtime.create(unit);
        } catch (RuntimeException e) {
            // RUNTIME_UNAVAILABLE, not SANDBOX_LOST: nothing has started yet, so nothing was lost.
            // The daemon is down or refusing, which is a different person looking in a different
            // place from an eviction mid-run — and that discrimination is the whole point of FR-F9.
            return failure(command, "RUNTIME_UNAVAILABLE", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        // Announced BEFORE observing, so a run that then hangs is still findable by its unit.
        // Guarded: this is bookkeeping about the run, and a caller that throws here must not cost
        // the run its outcome — the unit exists whether or not anybody recorded that it does.
        announce(command, handle, observer);
        return observe(command, adapter, handle, observer);
    }

    /**
     * Both streams are read CONCURRENTLY: the publisher reports pushes while the agent is still
     * working (continuous checkpointing, RUN-TOPOLOGY §5), so reading them in sequence would hold
     * every push report until the run ended. A throwing salvage or reader must not lose the run's
     * result or leave a reader blocked on a log stream for the life of the process: the siblings
     * are cancelled, what the publisher had reported is kept, and the unit is preserved by label.
     */
    /**
     * Tell the caller the unit exists. Guarded: this is bookkeeping about the run, and a caller
     * that throws here must not cost the run its outcome — the unit exists whether or not anybody
     * recorded that it does, and it carries the label either way.
     */
    private static void announce(RunCommand.ExecuteRun command, RunHandle handle, RunObserver observer) {
        try {
            observer.unitCreated(handle.providerRunId());
        } catch (RuntimeException e) {
            LOG.warnf("run %s: its unit could not be announced (%s); the sandbox is labelled either way",
                    command.runId(), e.getClass().getSimpleName());
        }
    }

    private RunResult observe(RunCommand.ExecuteRun command, HarnessAdapter adapter, RunHandle handle,
                              RunObserver observer) {
        RunEventFold seen = new RunEventFold();
        PublisherOutcome outcome = new PublisherOutcome();
        // submit(), not CompletableFuture.runAsync(): only an ExecutorService Future's cancel(true)
        // interrupts the running task. CompletableFuture.cancel documents that its flag "has no
        // effect", so the readers it "cancelled" kept blocking on the follow stream for the life of
        // the process.
        RunEventStream transcript = new RunEventStream(command.runId(), failures.scrubFor(command), observer::event);
        Future<?> agentStream = streams.submit(() ->
                runtime.attach(handle, LogChannel.AGENT, line -> adapter.parse(line).ifPresent(event -> {
                    // One parse, two readers. The fold decides the run's outcome and stays bounded;
                    // the transcript is the operator-facing tier and is bounded separately.
                    seen.accept(event);
                    transcript.accept(event);
                })));
        Future<?> publisherStream = streams.submit(() ->
                runtime.attach(handle, LogChannel.PUBLISHER, outcome::accept));

        Finalization finalization;
        try {
            finalization = runtime.salvage(handle);
        } catch (RuntimeException e) {
            // The readers get a moment to deliver what the publisher already wrote — the pushed
            // ref is the one fact worth carrying out of here — and are then interrupted, because a
            // follow stream on a container that is still running would otherwise never end.
            awaitBriefly(agentStream, publisherStream);
            LOG.errorf(e, "run %s: salvage failed; the unit is preserved for inspection", command.runId());
            // The SAME rule as an overrun, and for the same reason: nobody observed the agent's
            // exit, so a push the publisher already reported is still a push. This used to build a
            // bare failure that carried the ref in its detail TEXT only, so the run's record had no
            // pushed_ref and an operator was sent hunting for a branch that exists. Ranking it here
            // also gives a gate refusal and a forge rejection their own outcomes on this path.
            return unobserved(command, adapter, new Observed(seen, outcome,
                    Finalization.faulted(e.getClass().getSimpleName() + ": " + e.getMessage())));
        }
        // A reader fault after a good salvage is a logging fault, not a run fault: the exit code and
        // whatever was read still decide the result, and the unit is still destroyed.
        for (Future<?> reader : List.of(agentStream, publisherStream)) {
            try {
                reader.get();
            } catch (ExecutionException e) {
                LOG.errorf(e.getCause(), "run %s: a log reader failed; interpreting what was read", command.runId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        RunResult result = interpret(command, adapter, new Observed(seen, outcome, finalization));

        // destroy ONLY after salvage succeeded. A failed salvage preserves the unit so an operator
        // can read what the agent was doing — throwing that away is the loss salvage prevents.
        if (finalization.salvaged()) {
            // Guarded for the same reason its sibling below is, and the reason is sharper here:
            // by this line the run's outcome AND its measured spend are already decided, so a
            // daemon blip during teardown would propagate out of launch, be caught by the
            // dispatcher, and rewrite a successful run as WORKER_FAILED with its usage dropped —
            // discarding exactly the charge the ledger exists to keep. A leaked unit is
            // recoverable by its label; a discarded result is not.
            try {
                runtime.destroy(handle);
                // The ONE place a unit is known gone. Reporting it here rather than letting the
                // caller infer it from the result is what closed four leaking paths: an init
                // failure the arm deliberately leaves behind, a throwing salvage after a publisher
                // failure, this catch, and anything escaping the loop above.
                observer.unitReleased();
            } catch (RuntimeException e) {
                LOG.warnf("run %s: the unit could not be destroyed (%s); it is labelled for cleanup"
                        + " and keeps its lease, because it is still there",
                        command.runId(), e.getClass().getSimpleName());
            }
        } else {
            // Preserved, and STOPPED. That an overrun kills the agent is one arm's private promise,
            // not something the SPI states — and a run now reported finished makes an operator rely
            // on it. cancel() is an idempotent kill, and the publisher has already had its drain
            // window inside salvage, so nothing is lost. A throwing cancel must not lose the result.
            try {
                runtime.cancel(handle);
            } catch (RuntimeException e) {
                LOG.warnf("run %s: the preserved unit could not be stopped (%s)",
                        command.runId(), e.getClass().getSimpleName());
            }
            LOG.warnf("run %s preserved for inspection: %s", command.runId(), finalization.detail());
        }
        return result;
    }

    /** Delegated so the dispatcher's failures get the same scrub and the same retry answer. */
    private RunResult.RunFailed failure(RunCommand.ExecuteRun command, String cause, String detail) {
        return failures.of(command, cause, detail);
    }

    /**
     * A run whose exit nobody observed — it overran, or the runtime could not look.
     *
     * <p>The outcomes are ranked exactly as {@link #interpret} ranks them for a salvaged run, which
     * the first version of this branch did not do: it looked only for a push, so a gate refusal fell
     * through to a timeout with its blocked paths discarded and the refusal's attention row never
     * fired, and a forge rejecting the final checkpoint — the case FR-F7 uses as its example — was
     * reported as the clock running out around it.
     */
    private RunResult unobserved(RunCommand.ExecuteRun command, HarnessAdapter adapter, Observed observed) {
        PublisherOutcome outcome = observed.outcome();
        Finalization finalization = observed.finalization();
        warnOmittedPaths(command, outcome);
        if (outcome.refused()) {
            // The gate's refusal is the run's outcome whether or not the agent exited. Buried under
            // the overrun it hid the one row RUN_PUSH_GATE_REFUSED exists to raise.
            return new RunResult.RunFinished(command.runId(), null,
                    outcome.changedPaths(), outcome.blockedPaths(), usageOf(adapter, observed.seen().summary()),
                    true);
        }
        if (outcome.pushedRef().isPresent()) {
            // The work is on the branch, so this is finished — but NOT complete, and the result says
            // both. Usage is carried: the fold measured it, and a run that spent its entire wall
            // clock is the most expensive one the system produces, so reporting "unknown" here would
            // lose the largest charge there is.
            return new RunResult.RunFinished(command.runId(), outcome.pushedRef().orElseThrow(),
                    outcome.changedPaths(), outcome.blockedPaths(), usageOf(adapter, observed.seen().summary()),
                    true);
        }
        Map<String, Long> spent = usageOf(adapter, observed.seen().summary());
        if (outcome.failureCause().isPresent()) {
            // A forge that rejected the final checkpoint is the fact worth classifying, not the
            // clock that ran out around it.
            return failure(command, outcome.failureCause().orElseThrow(),
                    outcome.failureDetail() + "; " + finalization.detail()).withUsage(spent);
        }
        RunFailureCause cause = finalization.overran()
                ? RunFailureCause.AGENT_TIMEOUT
                : RunFailureCause.SALVAGE_FAILED;
        // A run that spent its whole wall clock and then failed is the most expensive outcome
        // the system produces. Reporting it without its usage leaves the spend cap blind to
        // exactly the runs most likely to be run again.
        return failure(command, cause.name(), finalization.detail()).withUsage(spent);
    }

    private static void warnOmittedPaths(RunCommand.ExecuteRun command, PublisherOutcome outcome) {
        if (outcome.omittedPaths() > 0) {
            LOG.warnf("run %s: %d changed or blocked paths beyond the result's cap are not listed",
                    command.runId(), outcome.omittedPaths());
        }
    }

    private RunResult interpret(RunCommand.ExecuteRun command, HarnessAdapter adapter, Observed observed) {
        PublisherOutcome outcome = observed.outcome();
        Finalization finalization = observed.finalization();
        if (!finalization.salvaged()) {
            return unobserved(command, adapter, observed);
        }
        if (outcome.failureCause().isPresent() && outcome.pushedRef().isEmpty() && !outcome.refused()) {
            // The publisher failed, but the AGENT ran to completion first and bought its tokens.
            return failure(command, outcome.failureCause().orElseThrow(), outcome.failureDetail())
                    .withUsage(usageOf(adapter, observed.seen().summary()));
        }
        if (observed.seen().dropped() > 0) {
            LOG.debugf("run %s: %d agent events folded away", command.runId(), observed.seen().dropped());
        }
        warnOmittedPaths(command, outcome);
        RunEventSummary summary = observed.seen().summary();
        TerminalOutcome terminal = adapter.classify(finalization.exitCode(), summary);
        if (!terminal.succeeded() && outcome.pushedRef().isEmpty() && !outcome.refused()) {
            // The agent failed and nothing reached the remote. A run that DID push before failing
            // is reported as finished, because the work is on the branch either way.
            //
            // Its usage rides along regardless: an agent that ran and then failed spent exactly
            // as much as one that ran and succeeded, and this failure is the only record of it
            // the orchestrator will ever see.
            return failure(command, terminal.cause().orElseThrow().name(), terminal.detail())
                    .withUsage(usageOf(adapter, summary));
        }
        return new RunResult.RunFinished(command.runId(), outcome.pushedRef().orElse(null),
                outcome.changedPaths(), outcome.blockedPaths(), usageOf(adapter, summary), false);
    }

    private static void awaitBriefly(Future<?> agentStream, Future<?> publisherStream) {
        long deadline = System.nanoTime() + READER_GRACE.toNanos();
        for (Future<?> reader : List.of(agentStream, publisherStream)) {
            try {
                reader.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                // A reader that failed or is still following: interrupted below either way.
            }
        }
        agentStream.cancel(true);
        publisherStream.cancel(true);
    }


    /**
     * Token usage as a plain map, or null.
     *
     * <p>Null IS unknown, and that is the only way this method reports it. Returning an empty map
     * would make "the harness said nothing" indistinguishable from "the harness measured zero" —
     * the fabricated zero ADR-023 exists to prevent, and the reason {@code UsageReport} refuses to
     * answer a count it never measured.
     */
    private static Map<String, Long> usageOf(HarnessAdapter adapter, RunEventSummary summary) {
        UsageReport report = adapter.usage(summary);
        if (report.isUnknown()) {
            return null;
        }
        Map<String, Long> usage = new LinkedHashMap<>();
        report.asMap().orElseThrow().forEach((bucket, count) -> usage.put(bucket.name(), count));
        return usage.isEmpty() ? null : usage;
    }
}
