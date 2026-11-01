package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
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
 *
 * <p>A harness review run's answer (harness reviews, step 4) takes the same path with a larger cap, and one difference:
 * an answer over its cap is a paid answer, so it comes back as a {@code RunReviewArtifact} marked over cap with the
 * usage it bought, never as {@code ARTIFACT_MISSING} and never as a lost run.
 */
final class ReadOnlyResults {

    private final RunFailures failures;

    private final RunCommand.ExecuteWorkDraftRun draft;

    private final RunCommand.ExecuteReviewRun review;

    ReadOnlyResults(RunFailures failures, RunCommand.ExecuteWorkDraftRun draft) {
        this.failures = failures;
        this.draft = draft;
        this.review = null;
    }

    /** A harness review run's answer (harness reviews, step 4). */
    ReadOnlyResults(RunFailures failures, RunCommand.ExecuteReviewRun review) {
        this.failures = failures;
        this.draft = null;
        this.review = review;
    }

    RunResult of(RunResult interpreted, PublisherOutcome outcome, long activeWallSeconds) {
        if (!(interpreted instanceof RunResult.RunFinished finished)) {
            return interpreted;
        }
        Spent spent = new Spent(finished.tokenUsage(), activeWallSeconds);
        if (!finished.agentUnobserved() && review != null && outcome.artifactOverCap()) {
            return overCap(spent);
        }
        Optional<String> handed = outcome.artifact();
        if (finished.agentUnobserved() || handed.isEmpty()) {
            return missing("the run handed over no artifact", outcome, finished.tokenUsage());
        }
        String text = failures.scrubFor(execution()).clean(ControlCharacters.strip(handed.orElseThrow()));
        if (text.isBlank()) {
            return missing("the artifact was empty", outcome, finished.tokenUsage());
        }
        // Redaction replaces a secret with a marker that can be longer than it, so a text at the cap can grow past it.
        if (text.getBytes(StandardCharsets.UTF_8).length > maxTextBytes()) {
            return review != null ? overCap(spent)
                    : missing("the artifact is over its cap once credentials are redacted", outcome, finished.tokenUsage());
        }
        return answer(text, spent, outcome);
    }

    /** What the run bought and how long its agent was active, carried onto whichever result it ends as. */
    private record Spent(Map<String, Long> usage, long activeWallSeconds) {
    }

    private RunResult answer(String text, Spent spent, PublisherOutcome outcome) {
        if (review != null) {
            return new RunReviewArtifact(review.runId(), review.review(), text, false, spent.usage(),
                    spent.activeWallSeconds(), List.of());
        }
        return new RunWorkArtifact(draft.runId(), draft.draft(), text, outcome.repositoryChanged() > 0,
                spent.usage(), spent.activeWallSeconds(), List.of());
    }

    /** A review answer over its cap: no text, the usage it bought, and the mark that says why. */
    private RunReviewArtifact overCap(Spent spent) {
        return new RunReviewArtifact(review.runId(), review.review(), null, true, spent.usage(),
                spent.activeWallSeconds(), List.of());
    }

    private RunCommand.ExecuteRun execution() {
        return review != null ? review.execution() : draft.execution();
    }

    private int maxTextBytes() {
        return review != null ? RunReviewArtifact.MAX_TEXT_BYTES : RunWorkArtifact.MAX_TEXT_BYTES;
    }

    private RunResult.RunFailed missing(String why, PublisherOutcome outcome, Map<String, Long> usage) {
        List<String> refusals = outcome.handoffRefusals();
        String detail = refusals.isEmpty() ? why : why + "; refused: " + String.join(", ", refusals);
        return failures.of(execution(), RunFailureCause.ARTIFACT_MISSING.name(), detail).withUsage(usage);
    }
}
