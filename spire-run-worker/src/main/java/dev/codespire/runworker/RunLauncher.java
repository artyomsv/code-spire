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
import dev.codespire.runtime.ModelProxyReport;
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
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Everything a run left behind for {@link #interpret}: the events, the publisher's report, the exit, and what the
     * unit's egress proxy and model proxy saw.
     */
    private record Observed(RunEventFold seen, PublisherOutcome outcome, Finalization finalization,
                            List<RunResult.EgressAttempt> egress, ModelProxyReport modelProxy) {
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
        return launch(command, null, observer, unit -> {});
    }

    public RunResult launchHeld(RunCommand.ExecuteWorkRun command, RunObserver observer,
                                java.util.function.Consumer<RunUnitSpec> prepared) {
        return launch(command.execution(), command, observer, prepared);
    }

    /**
     * A specification or plan run (M4 slice 3): a read-only unit whose artifact comes back as {@code RunWorkArtifact}.
     * The unit is destroyed once the result is decided, like an ordinary run's, and is never retained as held.
     */
    public RunResult launchReadOnly(RunCommand.ExecuteWorkDraftRun draft, RunObserver observer) {
        return launch(draft.execution(), draft, observer, unit -> {});
    }

    /**
     * A harness review run (harness reviews, step 4): a read-only unit whose answer comes back as
     * {@code RunReviewArtifact}, or {@code ARTIFACT_MISSING} when the agent handed over nothing.
     */
    public RunResult launchReview(RunCommand.ExecuteReviewRun review, RunObserver observer) {
        return launch(review.execution(), review, observer, unit -> {});
    }

    /**
     * {@code wrapper} says which unit this is: null for an ordinary run, the held build, the read-only draft, or the
     * read-only review run.
     * One parameter rather than one per kind, because at most one of them is ever present.
     */
    private RunResult launch(RunCommand.ExecuteRun command, RunCommand wrapper,
                             RunObserver observer, java.util.function.Consumer<RunUnitSpec> prepared) {
        Launch launch;
        RunUnitSpec unit;
        try {
            launch = new Launch(command, wrapper, harnesses.forName(command.harness()), observer);
            if (launch.work() != null && !(runtime instanceof dev.codespire.runtime.PublicationRuntime)) {
                throw new IllegalArgumentException("The runtime cannot retain publication");
            }
            unit = topology(command, wrapper, launch.adapter());
        } catch (RuntimeException e) {
            // Nothing was created, so nothing needs salvaging. Not retryable: the same command
            // would be rejected identically, and retrying a malformed dispatch is a loop.
            return failure(command, "BAD_COMMAND", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        RunResult.RunFailed unstartable = modelProxyImageMissing(command, unit);
        if (unstartable != null) return unstartable;
        return start(launch, unit, prepared);
    }

    /** Creates the unit, announces it, and observes it to its result. */
    private RunResult start(Launch launch, RunUnitSpec unit, java.util.function.Consumer<RunUnitSpec> prepared) {
        RunHandle handle;
        long activeStarted = System.nanoTime();
        try {
            // Persist the exact topology before any resource exists; a resumed publisher must
            // neither rebuild an agent invocation nor depend on today's deployment image settings.
            prepared.accept(unit);
            handle = launch.work() == null ? runtime.create(unit)
                    : ((dev.codespire.runtime.PublicationRuntime) runtime).createHeld(unit,
                            new dev.codespire.runtime.PublicationKey(launch.work().work().publicationKey()));
        } catch (RuntimeException e) {
            // RUNTIME_UNAVAILABLE, not SANDBOX_LOST: nothing has started yet, so nothing was lost.
            // The daemon is down or refusing, which is a different person looking in a different
            // place from an eviction mid-run — and that discrimination is the whole point of FR-F9.
            return failure(launch.command(), "RUNTIME_UNAVAILABLE", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        // Built before the unit is announced, because the announcement hands it over: it is this
        // run's numbering authority for its transcript, and anything adding a line to that run --
        // the log readers here, an operator's steer on the control channel -- must go through this
        // one instance rather than counting for itself. See RunNotes.
        Watchers watchers = new Watchers(launch.observer(), new RunEventStream(launch.command().runId(),
                failures.scrubFor(launch.command()), launch.observer()::event));
        // Announced BEFORE observing, so a run that then hangs is still findable by its unit.
        // Guarded: this is bookkeeping about the run, and a caller that throws here must not cost
        // the run its outcome — the unit exists whether or not anybody recorded that it does.
        announce(launch.command(), handle, watchers);
        return observe(new Live(launch, handle, watchers, activeStarted));
    }

    /**
     * A proxied unit whose model proxy image is missing fails before anything exists, as IMAGE_UNAVAILABLE: its lane
     * stops, the worker does not ({@link ModelProxyImageCheck} only logs it at startup). Null when it can start.
     */
    private RunResult.RunFailed modelProxyImageMissing(RunCommand.ExecuteRun command, RunUnitSpec unit) {
        return ModelProxyResults.imageMissing(runtime, unit)
                .map(missing -> failure(command, RunFailureCause.IMAGE_UNAVAILABLE.name(), missing))
                .orElse(null);
    }

    private RunUnitSpec topology(RunCommand.ExecuteRun command, RunCommand wrapper, HarnessAdapter adapter) {
        if (wrapper instanceof RunCommand.ExecuteWorkRun held) return builder.buildHeld(held, adapter);
        if (wrapper instanceof RunCommand.ExecuteWorkDraftRun draft) return builder.buildReadOnly(draft, adapter);
        if (wrapper instanceof RunCommand.ExecuteReviewRun review) return builder.buildReview(review, adapter);
        return builder.build(command, adapter);
    }

    /** Active wall time in whole seconds, rounded up, so a run that ran at all never reports zero. */
    static long wallSecondsSince(long activeStarted) {
        return (System.nanoTime() - activeStarted + 999_999_999L) / 1_000_000_000L;
    }

    /**
     * The caller's two views of a live run: what it is told, and where a line joins the transcript.
     *
     * <p>One parameter rather than two because they travel together everywhere and are created
     * together — and because the transcript stream is derived from the observer, so a method holding
     * one without the other could number a run's events into a sink nobody is reading.
     */
    private record Watchers(RunObserver observer, RunEventStream transcript) {
    }

    /**
     * Tell the caller the unit exists. Guarded: this is bookkeeping about the run, and a caller
     * that throws here must not cost the run its outcome — the unit exists whether or not anybody
     * recorded that it does, and it carries the label either way.
     */
    private static void announce(RunCommand.ExecuteRun command, RunHandle handle, Watchers watchers) {
        try {
            watchers.observer().unitCreated(handle.providerRunId(), watchers.transcript());
        } catch (RuntimeException e) {
            LOG.warnf("run %s: its unit could not be announced (%s); the sandbox is labelled either way",
                    command.runId(), e.getClass().getSimpleName());
        }
    }

    /** The run being launched: its command, which unit it is ({@code wrapper}, as launch says), its harness. */
    private record Launch(RunCommand.ExecuteRun command, RunCommand wrapper, HarnessAdapter adapter,
                          RunObserver observer) {
        /** The held build, or null for every other unit. */
        RunCommand.ExecuteWorkRun work() {
            return wrapper instanceof RunCommand.ExecuteWorkRun held ? held : null;
        }
    }

    /** A unit that exists: what launched it, its handle, its watchers, and when its active time began. */
    private record Live(Launch launch, RunHandle handle, Watchers watchers, long activeStarted) {
        RunCommand.ExecuteRun command() {
            return launch.command();
        }
    }

    /** A live unit's two log readers, and what they fill: the agent's events and the publisher's report. */
    private record Readers(Future<?> agent, Future<?> publisher, RunEventFold seen, PublisherOutcome outcome) {
    }

    /**
     * Both streams are read CONCURRENTLY: the publisher reports pushes while the agent is still
     * working (continuous checkpointing, RUN-TOPOLOGY §5), so reading them in sequence would hold
     * every push report until the run ended. A throwing salvage or reader must not lose the run's
     * result or leave a reader blocked on a log stream for the life of the process: the siblings
     * are cancelled, what the publisher had reported is kept, and the unit is preserved by label.
     */
    private RunResult observe(Live live) {
        Readers readers = read(live);
        Finalization finalization;
        try {
            finalization = runtime.salvage(live.handle());
        } catch (RuntimeException e) {
            return salvageFailed(live, readers, e);
        }
        awaitReaders(live.command(), readers);
        // After salvage, when the proxy has seen everything the agent asked for, and before destroy removes it.
        SidecarRecords sidecars = SidecarRecords.read(runtime, live.handle(), failures.scrubFor(live.command()));
        RunResult result = EgressResults.attach(decide(live, new Observed(readers.seen(), readers.outcome(),
                finalization, sidecars.egress(), sidecars.modelProxy())), sidecars.egress());
        // destroy ONLY after salvage succeeded. A failed salvage preserves the unit so an operator
        // can read what the agent was doing — throwing that away is the loss salvage prevents.
        UnitRelease release = new UnitRelease(runtime, live.command().runId());
        if (finalization.salvaged() && live.launch().work() == null) {
            release.destroy(live.handle(), live.watchers().observer());
        } else if (!finalization.salvaged()) {
            release.stopPreserved(live.handle(), finalization);
        }
        return result;
    }

    /**
     * submit(), not CompletableFuture.runAsync(): only an ExecutorService Future's cancel(true)
     * interrupts the running task. CompletableFuture.cancel documents that its flag "has no
     * effect", so the readers it "cancelled" kept blocking on the follow stream for the life of
     * the process.
     */
    private Readers read(Live live) {
        RunEventFold seen = new RunEventFold();
        // A review's answer may be as large as the review cap its unit hands the publisher; every other unit keeps 32 KiB.
        PublisherOutcome outcome = live.launch().wrapper() instanceof RunCommand.ExecuteReviewRun
                ? PublisherOutcome.withArtifactCap(dev.codespire.contract.event.RunReviewArtifact.MAX_TEXT_BYTES)
                : new PublisherOutcome();
        // One stop per run, however many lines the publisher emits after refusing.
        AtomicBoolean refusalStops = new AtomicBoolean();
        Future<?> agentStream = readAgent(live, seen);
        Future<?> publisherStream = streams.submit(() ->
                runtime.attach(live.handle(), LogChannel.PUBLISHER, line -> {
                    outcome.accept(line);
                    if (outcome.refused() && refusalStops.compareAndSet(false, true)) {
                        new UnitRelease(runtime, live.command().runId()).stopAgent(live.handle());
                    }
                }));
        return new Readers(agentStream, publisherStream, seen, outcome);
    }

    private Future<?> readAgent(Live live, RunEventFold seen) {
        HarnessAdapter adapter = live.launch().adapter();
        RunEventStream transcript = live.watchers().transcript();
        return streams.submit(() ->
                runtime.attach(live.handle(), LogChannel.AGENT, line -> adapter.parse(line).ifPresent(event -> {
                    // One parse, two readers. The fold decides the run's outcome and stays bounded;
                    // the transcript is the operator-facing tier and is bounded separately.
                    seen.accept(event);
                    transcript.accept(event);
                })));
    }

    private RunResult salvageFailed(Live live, Readers readers, RuntimeException e) {
        // The readers get a moment to deliver what the publisher already wrote — the pushed
        // ref is the one fact worth carrying out of here — and are then interrupted, because a
        // follow stream on a container that is still running would otherwise never end.
        awaitBriefly(readers.agent(), readers.publisher());
        LOG.errorf(e, "run %s: salvage failed; the unit is preserved for inspection", live.command().runId());
        SidecarRecords sidecars = SidecarRecords.read(runtime, live.handle(), failures.scrubFor(live.command()));
        // The SAME rule as an overrun, and for the same reason: nobody observed the agent's
        // exit, so a push the publisher already reported is still a push. This used to build a
        // bare failure that carried the ref in its detail TEXT only, so the run's record had no
        // pushed_ref and an operator was sent hunting for a branch that exists. Ranking it here
        // also gives a gate refusal and a forge rejection their own outcomes on this path.
        return EgressResults.attach(unobserved(live.command(), live.launch().adapter(), new Observed(readers.seen(),
                readers.outcome(), Finalization.faulted(e.getClass().getSimpleName() + ": " + e.getMessage()),
                sidecars.egress(), sidecars.modelProxy())), sidecars.egress());
    }

    /**
     * A reader fault after a good salvage is a logging fault, not a run fault: the exit code and
     * whatever was read still decide the result, and the unit is still destroyed.
     */
    private static void awaitReaders(RunCommand.ExecuteRun command, Readers readers) {
        for (Future<?> reader : List.of(readers.agent(), readers.publisher())) {
            try {
                reader.get();
            } catch (ExecutionException e) {
                LOG.errorf(e.getCause(), "run %s: a log reader failed; interpreting what was read", command.runId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The result of a salvaged or unobserved unit: interpreted, then shaped for a held build or a read-only unit. */
    private RunResult decide(Live live, Observed observed) {
        RunCommand.ExecuteRun command = live.command();
        RunResult result = interpret(command, live.launch().adapter(), observed, live.launch().work() != null);
        if (live.launch().work() != null && result instanceof RunResult.RunFinished finished && !finished.refused()
                && !finished.agentUnobserved()) {
            result = new HeldResults(failures, live.launch().work())
                    .of(finished, observed.outcome(), live.activeStarted());
        }
        if (live.launch().wrapper() instanceof RunCommand.ExecuteWorkDraftRun draft) {
            // The named read-only branch (A1), decided before the destroy below. A failed salvage returned earlier,
            // through the same unobserved ranking as any run, and preserves the unit for inspection.
            result = new ReadOnlyResults(failures, draft).of(result, observed.outcome(),
                    wallSecondsSince(live.activeStarted()));
        } else if (live.launch().wrapper() instanceof RunCommand.ExecuteReviewRun review) {
            // The same branch for a review's answer (harness reviews, step 4): its unit is destroyed below like a draft's.
            result = new ReadOnlyResults(failures, review).of(result, observed.outcome(),
                    wallSecondsSince(live.activeStarted()));
        }
        return result;
    }

    /**
     * A run the AGENT failed: when the model proxy saw a refused credential or an undeclared path, or the egress proxy
     * refused a host, that is the likely reason and the cause says so ({@link ModelProxyResults}). Only here: a
     * publisher failure, a gate refusal or a push keeps its own outcome, with the refused hosts beside it on the result.
     */
    private RunResult.RunFailed agentFailure(RunCommand.ExecuteRun command, String cause, String detail,
                                             Observed observed) {
        // The model proxy first: a refused credential or an undeclared model path explains the failure more exactly
        // than a refused host, and a refused credential is what marks the pool member (harness reviews step 3).
        java.util.Optional<ModelProxyResults.Explained> explained = ModelProxyResults.explain(observed.modelProxy(), detail);
        if (explained.isPresent()) {
            return failure(command, explained.orElseThrow().cause().name(), explained.orElseThrow().detail());
        }
        List<String> refused = EgressResults.refusedHosts(observed.egress());
        if (refused.isEmpty()) {
            return failure(command, cause, detail);
        }
        return failure(command, RunFailureCause.BLOCKED_EGRESS.name(), EgressResults.blockedDetail(refused, detail));
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
                    outcome.changedPaths(), outcome.blocked(), usageOf(adapter, observed.seen().summary()),
                    true, List.of());
        }
        if (outcome.pushedRef().isPresent()) {
            // The work is on the branch, so this is finished — but NOT complete, and the result says
            // both. Usage is carried: the fold measured it, and a run that spent its entire wall
            // clock is the most expensive one the system produces, so reporting "unknown" here would
            // lose the largest charge there is.
            return new RunResult.RunFinished(command.runId(), outcome.pushedRef().orElseThrow(),
                    outcome.changedPaths(), outcome.blocked(), usageOf(adapter, observed.seen().summary()),
                    true, List.of());
        }
        Map<String, Long> spent = usageOf(adapter, observed.seen().summary());
        if (outcome.failureCause().isPresent()) {
            // A forge that rejected the final checkpoint is the fact worth classifying, not the
            // clock that ran out around it.
            return failure(command, outcome.failureCause().orElseThrow(),
                    outcome.failureDetail() + "; " + finalization.detail()).withUsage(spent);
        }
        if (finalization.vanished()) {
            // The unit was gone when salvage looked: no container, no volume. Nothing exited where anybody saw it, so
            // no exit and no agent failure is claimed (and no refused host is blamed); the sandbox was lost.
            return failure(command, RunFailureCause.SANDBOX_LOST.name(), finalization.detail()).withUsage(spent);
        }
        RunFailureCause cause = finalization.overran()
                ? RunFailureCause.AGENT_TIMEOUT
                : RunFailureCause.SALVAGE_FAILED;
        // A run that spent its whole wall clock and then failed is the most expensive outcome
        // the system produces. Reporting it without its usage leaves the spend cap blind to
        // exactly the runs most likely to be run again.
        return agentFailure(command, cause.name(), finalization.detail(), observed).withUsage(spent);
    }

    private static void warnOmittedPaths(RunCommand.ExecuteRun command, PublisherOutcome outcome) {
        if (outcome.omittedPaths() > 0) {
            LOG.warnf("run %s: %d changed or blocked paths beyond the result's cap are not listed",
                    command.runId(), outcome.omittedPaths());
        }
    }

    private RunResult interpret(RunCommand.ExecuteRun command, HarnessAdapter adapter, Observed observed, boolean held) {
        PublisherOutcome outcome = observed.outcome();
        Finalization finalization = observed.finalization();
        if (!finalization.salvaged()) {
            return unobserved(command, adapter, observed);
        }
        if (outcome.failureCause().isPresent() && outcome.pushedRef().isEmpty() && !outcome.refused()
                && (!held || outcome.checkpointHead().isEmpty())) {
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
            return agentFailure(command, terminal.cause().orElseThrow().name(), terminal.detail(), observed)
                    .withUsage(usageOf(adapter, summary));
        }
        return new RunResult.RunFinished(command.runId(), outcome.pushedRef().orElse(null),
                outcome.changedPaths(), outcome.blocked(), usageOf(adapter, summary), false, List.of());
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
