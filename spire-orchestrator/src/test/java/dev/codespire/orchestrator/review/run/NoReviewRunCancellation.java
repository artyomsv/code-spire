package dev.codespire.orchestrator.review.run;

/**
 * A cancellation for saga fixtures that have no review runs: the real one reads factory_run and sends on the
 * run-control topic, which a plain unit test must never reach (CLAUDE.md, saga test fakes).
 */
public final class NoReviewRunCancellation extends ReviewRunCancellation {

    @Override
    public void supersede(String reviewId, String supersededCommit) {
        // nothing: these fixtures dispatch no review run
    }

    @Override
    public void close(String reviewId) {
        // nothing: these fixtures dispatch no review run
    }
}
