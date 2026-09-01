package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;

/**
 * Creates the run unit and reads its two log streams.
 *
 * <p>Performs no git and holds no filesystem — that is the whole point of ADR-038, and it is what
 * lets any replica salvage any run rather than only the one that started it.
 */
@ApplicationScoped
public class RunLauncher {

    private static final Logger LOG = Logger.getLogger(RunLauncher.class);

    /** One virtual thread per log stream; see {@link #launch} for why not the common pool. */
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

    public RunResult launch(RunCommand.ExecuteRun command) {
        HarnessAdapter adapter;
        RunUnitSpec unit;
        try {
            adapter = harnesses.forName(command.harness());
            unit = builder.build(command, adapter);
        } catch (RuntimeException e) {
            // Nothing was created, so nothing needs salvaging. Not retryable: the same command
            // would be rejected identically, and retrying a malformed dispatch is a loop.
            return new RunResult.RunFailed(command.runId(), "BAD_COMMAND",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        }

        RunHandle handle;
        try {
            handle = runtime.create(unit);
        } catch (RuntimeException e) {
            return new RunResult.RunFailed(command.runId(), "SANDBOX_UNREACHABLE",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }

        RunEventFold seen = new RunEventFold();
        PublisherOutcome outcome = new PublisherOutcome();

        // Both streams are read CONCURRENTLY. The publisher reports pushes while the agent is still
        // working (continuous checkpointing, RUN-TOPOLOGY §5), so reading them in sequence would
        // hold every push report until the run ended — and a run that then died would look as
        // though it had pushed nothing.
        // On a dedicated executor, never the common pool: each of these blocks for the run's whole
        // wall clock following a container log, and two per run on ForkJoinPool.commonPool() would
        // exhaust the JVM-wide pool with a handful of concurrent runs. Virtual threads, because the
        // work is a blocked socket read and nothing else.
        CompletableFuture<Void> agentStream = CompletableFuture.runAsync(() ->
                runtime.attach(handle, LogChannel.AGENT,
                        line -> adapter.parse(line).ifPresent(seen::accept)), streams);
        CompletableFuture<Void> publisherStream = CompletableFuture.runAsync(() ->
                runtime.attach(handle, LogChannel.PUBLISHER, outcome::accept), streams);

        Finalization finalization = runtime.salvage(handle);
        CompletableFuture.allOf(agentStream, publisherStream).join();

        RunResult result = interpret(command, adapter, seen, outcome, finalization);

        // destroy ONLY after salvage succeeded. A failed salvage preserves the unit so an operator
        // can read what the agent was doing — throwing that away is the loss salvage prevents.
        if (finalization.salvaged()) {
            runtime.destroy(handle);
        } else {
            LOG.warnf("run %s preserved for inspection: %s", command.runId(), finalization.detail());
        }
        return result;
    }

    private RunResult interpret(RunCommand.ExecuteRun command, HarnessAdapter adapter,
                                RunEventFold seen, PublisherOutcome outcome,
                                Finalization finalization) {
        if (!finalization.salvaged()) {
            return new RunResult.RunFailed(command.runId(), "SALVAGE_FAILED",
                    finalization.detail(), false);
        }
        if (outcome.failureCause().isPresent() && outcome.pushedRef().isEmpty() && !outcome.refused()) {
            return new RunResult.RunFailed(command.runId(), outcome.failureCause().orElseThrow(),
                    outcome.failureDetail(), true);
        }

        if (seen.dropped() > 0) {
            LOG.debugf("run %s: %d agent events folded away", command.runId(), seen.dropped());
        }
        RunEventSummary summary = seen.summary();
        TerminalOutcome terminal = adapter.classify(finalization.exitCode(), summary);
        if (!terminal.succeeded() && outcome.pushedRef().isEmpty() && !outcome.refused()) {
            // The agent failed and nothing reached the remote. A run that DID push before failing
            // is reported as finished, because the work is on the branch either way.
            return new RunResult.RunFailed(command.runId(),
                    terminal.cause().orElseThrow().name(), terminal.detail(), true);
        }
        return new RunResult.RunFinished(command.runId(), outcome.pushedRef().orElse(null),
                outcome.changedPaths(), outcome.blockedPaths(), usageOf(adapter, summary));
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
