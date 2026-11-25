package dev.codespire.orchestrator.review.labels;

import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * A newly admitted run's desired verdict label: none yet (verdict labels, V120; review waits for CI, V121). Recorded
 * only for a repository with "Verdict labels" on, so every earlier run's label command, late or replayed, converges the
 * pull request to "no verdict label" rather than to that run's label.
 */
@ApplicationScoped
public class RunLabels {

    private static final Logger LOG = Logger.getLogger(RunLabels.class);

    @Inject ReviewProjection projection;
    @Inject RepositoryRegistry repositories;
    @Inject DesiredLabels desired;

    /** In the admission of a run that waits for checks: a failure fails the admission, whose event is redelivered. */
    public void newRun(String reviewId, int round) {
        boolean on = projection.repositoryIdOf(reviewId).flatMap(id -> repositories.verdictLabels(id)).orElse(false);
        if (on) desired.record(new DesiredLabels.DesiredLabel(reviewId, round, null));
    }

    /**
     * For a run that starts at once: one idempotent write that never fails its admission (that path stays as it was).
     * Missed, an earlier run's late label command may put its label back until this run decides its own.
     */
    public void newRunQuietly(String reviewId, int round) {
        try {
            newRun(reviewId, round);
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "review %s: run %d's desired verdict label could not be cleared", reviewId, round);
        }
    }
}
