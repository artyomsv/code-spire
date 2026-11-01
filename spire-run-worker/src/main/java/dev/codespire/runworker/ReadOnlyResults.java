package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunWorkArtifact;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns what a read-only unit left behind into its one result (M4 slice 3, A1): the agent's artifact, scrubbed of
 * every credential the run was given, or {@code ARTIFACT_MISSING} with the usage the agent bought.
 *
 * <p>Scrubbed here, before it leaves the worker: the agent runs with the model key in its environment and can quote
 * it into the file, and the publisher's own redaction knows only the forge credential it was never given. Control
 * characters go first, so a key split by one cannot pass the scrub and be joined back by the orchestrator's own strip.
 * A failed agent keeps its own cause; this class sees only a run whose agent finished.
 */
final class ReadOnlyResults {

    private final RunFailures failures;

    private final RunCommand.ExecuteWorkDraftRun draft;

    ReadOnlyResults(RunFailures failures, RunCommand.ExecuteWorkDraftRun draft) {
        this.failures = failures;
        this.draft = draft;
    }

    RunResult of(RunResult interpreted, PublisherOutcome outcome, long activeWallSeconds) {
        if (!(interpreted instanceof RunResult.RunFinished finished)) {
            return interpreted;
        }
        Optional<String> handed = outcome.artifact();
        if (finished.agentUnobserved() || handed.isEmpty()) {
            return missing("the run handed over no artifact", outcome, finished.tokenUsage());
        }
        String text = failures.scrubFor(draft.execution()).clean(ControlCharacters.strip(handed.orElseThrow()));
        if (text.isBlank()) {
            return missing("the artifact was empty", outcome, finished.tokenUsage());
        }
        // Redaction replaces a secret with a marker that can be longer than it, so a text at the cap can grow past it.
        if (text.getBytes(StandardCharsets.UTF_8).length > RunWorkArtifact.MAX_TEXT_BYTES) {
            return missing("the artifact is over its cap once credentials are redacted", outcome, finished.tokenUsage());
        }
        return new RunWorkArtifact(draft.runId(), draft.draft(), text, outcome.repositoryChanged() > 0,
                finished.tokenUsage(), activeWallSeconds, List.of());
    }

    private RunResult.RunFailed missing(String why, PublisherOutcome outcome, Map<String, Long> usage) {
        List<String> refusals = outcome.handoffRefusals();
        String detail = refusals.isEmpty() ? why : why + "; refused: " + String.join(", ", refusals);
        return failures.of(draft.execution(), RunFailureCause.ARTIFACT_MISSING.name(), detail).withUsage(usage);
    }
}
