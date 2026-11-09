package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;

/**
 * Turns a held build's finished run into its one result: ready at the checkpoint the publisher held, or a publisher
 * failure when the work escaped the hold or no checkpoint was observed. A refused or unobserved run keeps the result
 * {@link RunLauncher} interpreted, and never reaches this class.
 */
final class HeldResults {

    private final RunFailures failures;

    private final RunCommand.ExecuteWorkRun work;

    HeldResults(RunFailures failures, RunCommand.ExecuteWorkRun work) {
        this.failures = failures;
        this.work = work;
    }

    RunResult of(RunResult.RunFinished finished, PublisherOutcome outcome, long activeStarted) {
        RunCommand.ExecuteRun command = work.execution();
        if (finished.pushedRef() != null) {
            return failures.of(command, "PUBLISHER_FAILED",
                    "Publication escaped the initial hold at " + finished.pushedRef()).withUsage(finished.tokenUsage());
        }
        if (outcome.checkpointHead().isPresent() && !emptyRefused(outcome)) {
            return new RunResult.RunWorkReady(command.runId(), work.work(), outcome.checkpointHead().orElseThrow(),
                    finished.changedPaths(), finished.tokenUsage(), RunLauncher.wallSecondsSince(activeStarted))
                    .withStepSummary(StepSummaries.from(outcome, failures.scrubFor(command)))
                    // Where it started, after any base fallback: the orchestrator compares it with the start it chose (A2).
                    .withStartFromHead(command.startFromHead())
                    .withEmpty(outcome.checkpointEmpty());
        }
        return failures.of(command, "PUBLISHER_FAILED", "The held build produced no observed checkpoint")
                .withUsage(finished.tokenUsage());
    }

    /**
     * An empty checkpoint is a ready only for a plan step allowed to finish empty that started from the base (M4 slice 3,
     * decision 14, amendment 3). A run started from a checkpoint always bundles its start, so an empty line from one means
     * it wrote no bundle and holds nothing: it is today's idle build, no observed checkpoint, and its predecessor is kept.
     */
    private boolean emptyRefused(PublisherOutcome outcome) {
        return outcome.checkpointEmpty() && (!work.emptyAllowed() || work.execution().startFromHead() != null);
    }
}
