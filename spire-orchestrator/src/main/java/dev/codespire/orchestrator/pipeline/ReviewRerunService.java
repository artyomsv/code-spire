package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.jboss.logging.Logger;

/**
 * Restarts an existing review's pipeline on its stored commit — the operator-triggered analog of the
 * {@code /review} PR command (backlog B4). It drives the ReviewLifecycle with {@code force=true}
 * ("a commit superseding itself = restart the run"), so it re-runs even on the same commit that a
 * normal re-register would treat as an idempotent no-op.
 *
 * <p>Because the worker caches the completed LLM result (and posted-comment slots) in its own
 * idempotency table and re-emits them for the same (reviewId, commit), a force re-request alone would
 * replay the stale result. So the worker claims are cleared first — the LLM genuinely runs again and
 * fresh comments are posted. Previously-posted PR comments are NOT retracted (a future enhancement),
 * so re-running a review that already commented will add new comments alongside the old ones.
 */
@ApplicationScoped
public class ReviewRerunService {

    private static final Logger LOG = Logger.getLogger(ReviewRerunService.class);

    @Inject
    ReviewProjection projection;

    @Inject
    ReviewLifecycleService lifecycle;

    @Inject
    CommandsEmitter commands;

    @Inject
    WorkerCredentials workerCredentials;

    /**
     * @return true if the run was (re)started; false if the aggregate declined it (should not happen
     *         with force=true).
     * @throws NotFoundException if the review or its workspace provider no longer exists.
     */
    public boolean rerun(String workspace, String slug, long pr) {
        RepoRef repo = new RepoRef(workspace, slug);
        String reviewId = ReviewIds.reviewId(repo, pr);
        String commit = projection.commitOf(reviewId)
                .orElseThrow(() -> new NotFoundException(
                        "No review for " + workspace + "/" + slug + "#" + pr));
        // Broker the credential by REVIEW so a workspace shared across SCMs resolves the
        // right provider (the review's stored SCM type), not just the oldest by name.
        String scmCredential = workerCredentials.packForReview(reviewId)
                .orElseThrow(() -> new NotFoundException("No enabled provider registered for workspace '"
                        + workspace + "'. Add one under Settings -> Providers."));

        // Drop the worker's cached result + comment claims BEFORE re-requesting, so the worker re-runs
        // the LLM instead of re-emitting the stored result.
        projection.clearWorkerIdempotency(reviewId);

        var emitted = lifecycle.handle(reviewId, new RecordCommand.RequestReview(commit, "rerun", true));
        boolean started = emitted.stream().anyMatch(DomainEvent.ReviewRequested.class::isInstance);
        if (!started) {
            return false;
        }
        projection.markRerunStarted(reviewId);
        commands.emit(new ActionCommand.FetchDiff(reviewId, repo, pr, commit, scmCredential));
        LOG.infof("Re-run requested for %s on commit %s", reviewId, commit);
        return true;
    }
}
