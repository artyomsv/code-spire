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
import java.util.function.Consumer;

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

    public RunResult launch(RunCommand.ExecuteRun command, Consumer<RunEventRecord> stream) {
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
        return observe(command, adapter, handle, stream);
    }

    /**
     * Both streams are read CONCURRENTLY: the publisher reports pushes while the agent is still
     * working (continuous checkpointing, RUN-TOPOLOGY §5), so reading them in sequence would hold
     * every push report until the run ended. A throwing salvage or reader must not lose the run's
     * result or leave a reader blocked on a log stream for the life of the process: the siblings
     * are cancelled, what the publisher had reported is kept, and the unit is preserved by label.
     */
    private RunResult observe(RunCommand.ExecuteRun command, HarnessAdapter adapter, RunHandle handle,
                              Consumer<RunEventRecord> stream) {
        RunEventFold seen = new RunEventFold();
        PublisherOutcome outcome = new PublisherOutcome();
        // submit(), not CompletableFuture.runAsync(): only an ExecutorService Future's cancel(true)
        // interrupts the running task. CompletableFuture.cancel documents that its flag "has no
        // effect", so the readers it "cancelled" kept blocking on the follow stream for the life of
        // the process.
        RunEventStream transcript = new RunEventStream(command.runId(), failures.scrubFor(command), stream);
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
            return failure(command, "SALVAGE_FAILED",
                    e.getClass().getSimpleName() + ": " + e.getMessage() + pushedNote(outcome));
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
            runtime.destroy(handle);
        } else {
            LOG.warnf("run %s preserved for inspection: %s", command.runId(), finalization.detail());
        }
        return result;
    }

    /** Delegated so the dispatcher's failures get the same scrub and the same retry answer. */
    private RunResult.RunFailed failure(RunCommand.ExecuteRun command, String cause, String detail) {
        return failures.of(command, cause, detail);
    }

    private RunResult interpret(RunCommand.ExecuteRun command, HarnessAdapter adapter, Observed observed) {
        PublisherOutcome outcome = observed.outcome();
        Finalization finalization = observed.finalization();
        if (!finalization.salvaged()) {
            // The work pushed before the overrun is on the branch; the detail says so, because a
            // failure that hides an hour of delivered checkpoints sends the operator to the wrong place.
            return failure(command, "SALVAGE_FAILED", finalization.detail() + pushedNote(outcome));
        }
        if (outcome.failureCause().isPresent() && outcome.pushedRef().isEmpty() && !outcome.refused()) {
            return failure(command, outcome.failureCause().orElseThrow(), outcome.failureDetail());
        }
        if (observed.seen().dropped() > 0) {
            LOG.debugf("run %s: %d agent events folded away", command.runId(), observed.seen().dropped());
        }
        if (outcome.omittedPaths() > 0) {
            LOG.warnf("run %s: %d changed or blocked paths beyond the result's cap are not listed",
                    command.runId(), outcome.omittedPaths());
        }
        RunEventSummary summary = observed.seen().summary();
        TerminalOutcome terminal = adapter.classify(finalization.exitCode(), summary);
        if (!terminal.succeeded() && outcome.pushedRef().isEmpty() && !outcome.refused()) {
            // The agent failed and nothing reached the remote. A run that DID push before failing
            // is reported as finished, because the work is on the branch either way.
            return failure(command, terminal.cause().orElseThrow().name(), terminal.detail());
        }
        return new RunResult.RunFinished(command.runId(), outcome.pushedRef().orElse(null),
                outcome.changedPaths(), outcome.blockedPaths(), usageOf(adapter, summary));
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

    private static String pushedNote(PublisherOutcome outcome) {
        return outcome.pushedRef().map(ref -> "; work pushed to " + ref + " before the failure").orElse("");
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
