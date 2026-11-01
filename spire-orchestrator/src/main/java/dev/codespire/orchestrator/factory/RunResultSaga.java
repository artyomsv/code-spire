package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunWorkArtifact;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Projects each run result onto the {@code factory_run} read model. Blocking, like every other
 * consumer here: the projection is a JDBC write, and a JDBC write on the event loop is the class
 * of stall this codebase has already paid for once.
 */
@ApplicationScoped
public class RunResultSaga {

    private static final Logger LOG = Logger.getLogger(RunResultSaga.class);

    private static final String MDC_RUN_ID = "runId";

    @Inject
    FactoryRunProjection projection;

    @Inject
    RunCharges charges;

    @Inject
    RunCredentialFeedback credentials;

    @Inject
    FactoryPullRequests pullRequests;

    @Inject
    dev.codespire.orchestrator.work.WorkItemRunBridge workItems;

    @Inject
    dev.codespire.orchestrator.work.WorkDraftResults drafts;

    @Inject
    dev.codespire.orchestrator.review.run.ReviewRunBindings reviewRuns;

    @Inject
    dev.codespire.orchestrator.review.run.ReviewRunCharges reviewCharges;

    @Inject
    dev.codespire.orchestrator.review.run.ReviewRunResults reviewResults;

    @Incoming("run-results-in")
    @Blocking
    public void on(RunResult result) {
        if (result == null) {
            // A poison record. Dropped, not dead-lettered: returning normally ACKS the record,
            // and cs.dlq is reached by a nack. The deserializer already logged it at ERROR, which
            // is the only trace there will be — said plainly because "it is on cs.dlq" sent a
            // reader to a screen that would be empty.
            return;
        }
        MDC.put(MDC_RUN_ID, result.runId());
        try {
            LOG.infof("run result %s", result.getClass().getSimpleName());
            // A draft artifact bound to another attempt is refused here too, before it can be projected or charged (M4 slice 3),
            // and so is a review answer naming a call its row does not store (harness reviews, step 4).
            if(!workItems.acceptsBinding(result) || !drafts.acceptsBinding(result) || !reviewRuns.acceptsBinding(result))return;
            projection.apply(result);
            // AFTER the projection, deliberately. The run's outcome is the fact an operator is
            // waiting on; the ledger write is best-effort and says so if it fails, so ordering it
            // first would let a ledger outage delay a terminal status that is already known.
            charges.record(result);
            credentials.reactTo(result);
            // A review run's result is the review's (harness reviews, step 4): charged once under its call slot, handed to
            // the review-run port, and no further. It has no draft or work-run row and pushed nothing to propose.
            if (projection.kindOf(result.runId()).filter(RunKind::isReview).isPresent()) {
                toReview(result);
                return;
            }
            // A specification or plan run pushed nothing to propose, and the work-run inbox holds no row for it: its own
            // inbox claims every result it has, a failure included, so none falls through to the proposal below.
            if (drafts.accept(result)) return;
            if (result instanceof RunWorkArtifact) return;
            if(workItems.accept(result))return;
            // LAST, and after the projection on purpose: it reads the row the projection just
            // wrote, it talks to a forge, and it must not delay the terminal status an operator is
            // waiting on. It records its own failures and throws none of them back here.
            pullRequests.propose(result);
        } finally {
            MDC.remove(MDC_RUN_ID);
        }
    }

    /** The call a review result answers: its own binding, or for a result that carries none (a failure), its row's. */
    private void toReview(RunResult result) {
        java.util.Optional<dev.codespire.contract.review.ReviewRunBinding> binding =
                result instanceof dev.codespire.contract.event.RunReviewArtifact answer
                        ? java.util.Optional.of(answer.review()) : projection.reviewBindingOf(result.runId());
        if (binding.isEmpty()) {
            LOG.errorf("review run %s has no review call on its row, so its result is neither charged nor recorded",
                    result.runId());
            return;
        }
        reviewCharges.record(result, binding.get());
        reviewResults.accept(result, binding.get());
    }
}
