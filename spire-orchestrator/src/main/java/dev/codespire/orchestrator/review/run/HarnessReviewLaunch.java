package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.factory.RunFunding;
import dev.codespire.orchestrator.llm.CallRefs;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.ReviewHarness;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Dispatches a prepared harness review's run (harness reviews, step 6): the worker has written the run's inputs, and
 * the launch checks that the review's round is still the one prepared, composes the prompt and dispatches the run with
 * those inputs. A refusal never falls back to the API (operator decision 6).
 *
 * <p>Decides in this order: a repository with no harness any more is {@link HarnessReviewStart.Refused} (plan call
 * 15); a round that cannot be read is refused; a round other than the prepared one is {@link HarnessReviewStart.Dropped};
 * otherwise the run is dispatched. A cancelled admission is dropped too: a forced re-run between the round check and
 * admission cancels it, and a refusal would refuse the new round on the same commit (plan check, item 2).
 */
@ApplicationScoped
public class HarnessReviewLaunch {

    /** What a refusal names as the harness when the repository has none any more. */
    static final String NO_HARNESS = "no harness";

    /** The repository was switched off its harness between prepare and launch. */
    static final String HARNESS_REMOVED = "review_harness_removed";

    private static final Logger LOG = Logger.getLogger(HarnessReviewLaunch.class);

    @Inject
    RepositoryRegistry repositories;

    @Inject
    ReviewProjection projection;

    @Inject
    FixTargets targets;

    @Inject
    ReviewRunDispatch dispatch;

    @Inject
    ReviewRuns runs;

    @Inject
    WorkerPromptTemplates promptTemplates;

    /** The repository a harness review runs for and the harness it runs on. */
    private record OnHarness(UUID repositoryId, ReviewHarness harness) {
    }

    public HarnessReviewStart.Decision launch(IntegrationEvent.HarnessReviewPrepared prepared) {
        Optional<OnHarness> onHarness = projection.repositoryIdOf(prepared.reviewId())
                .flatMap(id -> repositories.reviewHarness(id).map(harness -> new OnHarness(id, harness)));
        if (onHarness.isEmpty()) {
            return new HarnessReviewStart.Refused(NO_HARNESS, HARNESS_REMOVED);
        }
        String harness = onHarness.orElseThrow().harness().harness();
        // Fail-closed, as at the start: a guessed round would bind the run to a round that may not be the review's.
        int round = runs.roundOrUnknown(prepared.reviewId());
        if (round == ReviewRuns.UNKNOWN_RUN) {
            return new HarnessReviewStart.Refused(harness, HarnessReviewStart.ROUND_UNKNOWN);
        }
        // A forced re-run ended the prepared round while the worker worked: its inputs are never dispatched.
        if (round != prepared.round()) {
            return new HarnessReviewStart.Dropped("round " + prepared.round() + " ended");
        }
        return startedOrRefused(onHarness.orElseThrow(), prepared);
    }

    /**
     * A failure while composing the run's request refuses the review: thrown, it failed the saga's message and left the
     * review in "reviewing" with no run and no attention row (live on spire-test #93). The dispatch stays outside this
     * guard: once it may have published the command a paid run may be executing, and a terminal refusal would make its
     * answer arrive at a failed review and be dropped (review of PR #368, code-quality/M-2). A dispatch that throws fails
     * the message as before, and the review stays open for the run's answer.
     */
    private HarnessReviewStart.Decision startedOrRefused(OnHarness onHarness,
                                                         IntegrationEvent.HarnessReviewPrepared prepared) {
        String harness = onHarness.harness().harness();
        ReviewRunRequest request;
        try {
            request = request(onHarness, prepared);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Harness review launch failed for %s; the review is refused", prepared.reviewId());
            return new HarnessReviewStart.Refused(harness, HarnessReviewStart.START_FAILED);
        }
        return dispatched(request, harness);
    }

    private ReviewRunRequest request(OnHarness onHarness, IntegrationEvent.HarnessReviewPrepared prepared) {
        ReviewHarness harness = onHarness.harness();
        FixTargets.PushTarget target = targets.forReview(prepared.reviewId()).orElseThrow(
                () -> new IllegalStateException("The pull request of " + prepared.reviewId() + " is not recorded"));
        return new ReviewRunRequest(onHarness.repositoryId(), binding(prepared),
                new RunFunding.Choice(harness.harness(), harness.model(), harness.effort(), harness.subscription()),
                prompt(prepared, target), prepared.inputs());
    }

    private HarnessReviewStart.Decision dispatched(ReviewRunRequest request, String harness) {
        return switch (dispatch.dispatch(request)) {
            case ReviewRunDispatch.Dispatched sent -> new HarnessReviewStart.Started(sent.runId(), harness);
            case ReviewRunDispatch.AlreadyQueued queued -> new HarnessReviewStart.Started(queued.runId(), harness);
            // The queued row exists; a launch that never arrived surfaces as REVIEW_STUCK, not as a refusal.
            case ReviewRunDispatch.Uncertain uncertain -> new HarnessReviewStart.Started(uncertain.runId(), harness);
            // A supersede, a close or a forced re-run cancelled the admission: the review has moved on, refuse nothing.
            case ReviewRunDispatch.Refused refused when ReviewRunDispatch.CANCELLED_REASON.equals(refused.reason()) ->
                    new HarnessReviewStart.Dropped("round ended or pull request closed");
            case ReviewRunDispatch.Refused refused -> new HarnessReviewStart.Refused(harness, refused.reason());
        };
    }

    /** The review call this run answers: the prepared round's slot, so its one charge lands where an API call's would. */
    private static ReviewRunBinding binding(IntegrationEvent.HarnessReviewPrepared prepared) {
        return new ReviewRunBinding(prepared.reviewId(), prepared.kind(), prepared.prId(), prepared.commit(),
                CallRefs.reviewSlot(prepared.commit(), prepared.round()), prepared.round());
    }

    private String prompt(IntegrationEvent.HarnessReviewPrepared prepared, FixTargets.PushTarget target) {
        RepoRef repo = new RepoRef(target.workspace(), target.slug());
        HarnessReviewPrompt.ReviewTarget reviewTarget = new HarnessReviewPrompt.ReviewTarget(target.workspace(),
                target.slug(), prepared.prId(), prepared.commit(), target.destBranch());
        return switch (prepared.kind()) {
            case REVIEW -> HarnessReviewPrompt.compose(template(PromptKind.REVIEW, repo), reviewTarget);
            case RECONCILE -> HarnessReviewPrompt.composeReReview(template(PromptKind.REVIEW, repo),
                    template(PromptKind.RECONCILE, repo), reviewTarget);
            case FOLLOWUP -> throw new IllegalStateException("A reply is not prepared as a review run (step 7)");
        };
    }

    /** Null means no customized prompt: the API path leaves the default to the worker, a run gets it here. */
    private PromptTemplate template(PromptKind kind, RepoRef repo) {
        return Optional.ofNullable(promptTemplates.forKind(kind, repo))
                .orElseGet(() -> PromptCatalog.defaultTemplate(kind));
    }
}
