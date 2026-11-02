package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand.FinishHarnessReview;
import dev.codespire.contract.command.ActionCommand.PrepareHarnessReview;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.worker.adapters.WorkerScmClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Prepares and finishes a harness review (harness reviews, step 6). Prepare loads the context and, for a re-review,
 * numbers the prior open findings and persists that numbering; finish parses the agent's answer with the API path's
 * own parsers and backstops. The review worker never runs a harness (ADR-029): the run-worker does, between the two.
 */
@ApplicationScoped
public class HarnessReviewWorker {

    /** Every claim this worker writes starts with this, so {@code PostComments}' recovery can skip them all. */
    static final String CLAIM_PREFIX = "harness:";

    @Inject
    WorkerScmClients scm;

    @Inject
    CommentIdempotencyStore idempotency;

    @Inject
    ResultsEmitter results;

    @Inject
    AssembledContextReader contextReader;

    @Inject
    ObjectMapper mapper;

    /**
     * The prior findings exactly as the prepare numbered them, persisted under the prepare claim: verdict ids are
     * positions in this list, so finish judges against it and never a list recomputed later.
     */
    record PreparedPrior(String priorHead, boolean incremental, List<PriorFinding> numbered) {
    }

    public void prepare(PrepareHarnessReview command) {
        // not built yet
    }

    public void finish(FinishHarnessReview command) {
        // not built yet
    }
}
