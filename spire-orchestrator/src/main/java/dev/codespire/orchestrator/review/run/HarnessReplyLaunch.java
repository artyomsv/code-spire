package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.IntegrationEvent.HarnessReplyPrepared;
import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.factory.RunFunding;
import dev.codespire.orchestrator.llm.CallRefs;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.ReviewHarness;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Dispatches a prepared reply's run (harness reviews, step 7): the worker has rendered the thread, and the launch
 * composes the reply prompt and dispatches a {@code FOLLOWUP} run under the follow-up call slot, so its one charge lands
 * where the API reply's would. No round lock and no commit guard: a reply answers a thread, not a round (plan call 2).
 *
 * <p>A turn that already has a run is not dispatched again, whatever head its prepare read: the claim taken at prepare
 * blocks nothing until the post, so a redelivered prepare after a push would otherwise fund a second run whose charge
 * the ledger then drops (plan check, item 3). Every refusal is said under the thread and clears "answering"; a refusal
 * never falls back to the API (operator decision 6).
 */
@ApplicationScoped
public class HarnessReplyLaunch {

    private static final Logger LOG = Logger.getLogger(HarnessReplyLaunch.class);

    @Inject
    RepositoryRegistry repositories;

    @Inject
    ReviewProjection projection;

    @Inject
    FixTargets targets;

    @Inject
    ReviewRunDispatch dispatch;

    @Inject
    FactoryRunProjection runRows;

    @Inject
    WorkerPromptTemplates promptTemplates;

    @Inject
    TimelineBroadcaster timeline;

    /** The repository a reply runs for and the harness it runs on. */
    private record OnHarness(UUID repositoryId, ReviewHarness harness) {
    }

    public void launch(HarnessReplyPrepared prepared) {
        Optional<OnHarness> onHarness = projection.repositoryIdOf(prepared.reviewId())
                .flatMap(id -> repositories.reviewHarness(id).map(harness -> new OnHarness(id, harness)));
        if (onHarness.isEmpty()) {
            refuse(prepared, HarnessReviewLaunch.NO_HARNESS + ": " + HarnessReviewLaunch.HARNESS_REMOVED);
            return;
        }
        String harness = onHarness.orElseThrow().harness().harness();
        Optional<String> existing = runRows.reviewRunWithSlot(prepared.reviewId(), slot(prepared));
        if (existing.isPresent() && false) {
            timeline.record("result", "harness-reply", prepared.reviewId(),
                    "Already answering on " + harness + ", run " + existing.orElseThrow());
            return;
        }
        ReviewRunRequest request;
        try {
            request = request(onHarness.orElseThrow(), prepared);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Harness reply launch failed for %s; the reply is refused", prepared.reviewId());
            refuse(prepared, harness + ": " + HarnessReviewStart.START_FAILED);
            return;
        }
        // Outside the guard, as a review's: once the command may be published, a paid run may be executing.
        dispatched(prepared, harness, dispatch.dispatch(request));
    }

    private void dispatched(HarnessReplyPrepared prepared, String harness, ReviewRunDispatch.Outcome outcome) {
        switch (outcome) {
            case ReviewRunDispatch.Dispatched sent -> started(prepared, harness, sent.runId());
            case ReviewRunDispatch.AlreadyQueued queued -> started(prepared, harness, queued.runId());
            // The queued row exists; a launch that never arrived is seen on the Runs screen, not refused.
            case ReviewRunDispatch.Uncertain uncertain -> started(prepared, harness, uncertain.runId());
            // Only a close cancels a reply's admission: nobody waits in a closed pull request's threads.
            case ReviewRunDispatch.Refused refused when ReviewRunDispatch.CANCELLED_REASON.equals(refused.reason()) -> {
                timeline.record("result", "dropped:HarnessReplyPrepared", prepared.reviewId(), "pull request closed");
                said(prepared, "FollowUpRefused", "pull request closed");
            }
            case ReviewRunDispatch.Refused refused -> refuse(prepared, harness + ": " + refused.reason());
        }
    }

    private void started(HarnessReplyPrepared prepared, String harness, String runId) {
        timeline.record("result", "harness-reply", prepared.reviewId(), "Answering on " + harness + ", run " + runId);
    }

    private void refuse(HarnessReplyPrepared prepared, String reason) {
        timeline.record("result", "refused:HarnessReply", prepared.reviewId(), reason);
        said(prepared, "FollowUpRefused", "harness reply refused: " + reason);
        LOG.warnf("Harness reply refused for %s thread %s — %s", prepared.reviewId(), prepared.threadRef().value(),
                reason);
    }

    /** Persisted under the thread, so the conversation shows why no answer came; and the "responding" pill clears. */
    private void said(HarnessReplyPrepared prepared, String type, String detail) {
        projection.appendEvent(prepared.reviewId(), "result", type, detail, prepared.threadRef().value());
        projection.setAnswering(prepared.reviewId(), false);
    }

    private ReviewRunRequest request(OnHarness onHarness, HarnessReplyPrepared prepared) {
        ReviewHarness harness = onHarness.harness();
        FixTargets.PushTarget target = targets.forReview(prepared.reviewId()).orElseThrow(
                () -> new IllegalStateException("The pull request of " + prepared.reviewId() + " is not recorded"));
        ReviewRunBinding binding = new ReviewRunBinding(prepared.reviewId(), ReviewRunBinding.Kind.FOLLOWUP,
                prepared.prId(), prepared.commit(), slot(prepared), prepared.round());
        HarnessReviewPrompt.ReviewTarget reviewTarget = new HarnessReviewPrompt.ReviewTarget(target.workspace(),
                target.slug(), prepared.prId(), prepared.commit(), target.destBranch());
        return new ReviewRunRequest(onHarness.repositoryId(), binding,
                new RunFunding.Choice(harness.harness(), harness.model(), harness.effort(), harness.subscription()),
                HarnessReplyPrompt.compose(template(new RepoRef(target.workspace(), target.slug())), reviewTarget),
                prepared.inputs());
    }

    /** The API reply's own slot: thread and turn, so each turn is its own run and its own charge. */
    private static String slot(HarnessReplyPrepared prepared) {
        return CallRefs.followUpSlot(prepared.threadRef().value(), prepared.triggeringCommentId());
    }

    /** Null means no customized prompt: the API path leaves the default to the worker, a run gets it here. */
    private PromptTemplate template(RepoRef repo) {
        return Optional.ofNullable(promptTemplates.forKind(PromptKind.FOLLOWUP, repo))
                .orElseGet(() -> PromptCatalog.defaultTemplate(PromptKind.FOLLOWUP));
    }
}
