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
 * Whether a review's first round runs on a harness, and if so dispatches its review run (harness reviews, step 5).
 *
 * <p>Decides in this order: no repository or no review harness is {@link NotHarness}; a re-review (a prior round was
 * posted) is {@link NotHarness}, because re-reviews stay on the API until step 6; a fork pull request, or one whose
 * fork status is unknown, is {@link Refused}; a review whose round cannot be read is {@link Refused}; otherwise the run is dispatched, and a refused dispatch is
 * {@link Refused} with its reason. A refusal never falls back to the API (operator decision 6).
 *
 * <p>The texts the saga writes for each decision are built here, so {@code ResultSaga} only routes them.
 */
@ApplicationScoped
public class HarnessReviewStart {

    /** Forks wait for step 8: the agent would run the fork's code with the reviewer's clone credential. */
    static final String FORK_REFUSED = "fork_pull_request_not_reviewed_on_harness";

    /** The review's round could not be read, so no run is bound to one; a re-run asks again. */
    static final String ROUND_UNKNOWN = "review_round_unknown";

    /** Composing the run's prompt or request threw; the log has the cause. */
    static final String START_FAILED = "harness_review_start_failed";

    private static final Logger LOG = Logger.getLogger(HarnessReviewStart.class);

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

    /** The reviewer's packed forge credential a {@code PrepareHarnessReview} carries (step 6; not used yet). */
    @Inject
    dev.codespire.orchestrator.provider.WorkerCredentials credentials;

    /** Where a {@code PrepareHarnessReview} is sent (step 6; not used yet). */
    @Inject
    dev.codespire.orchestrator.pipeline.CommandsEmitter commands;

    /** What became of a review's start. */
    public sealed interface Decision permits NotHarness, Started, Refused, Preparing, Dropped {
    }

    /** The review takes today's API path. */
    public record NotHarness() implements Decision {
    }

    /** A review run was dispatched (or is already queued, or its launch is uncertain). */
    public record Started(String runId, String harness) implements Decision {

        /** The review's note while its run works. */
        public String note() {
            return "Reviewing on " + harness + ", run " + runId;
        }
    }

    /** The review is refused on its harness, never sent to the API instead. */
    public record Refused(String harness, String reason) implements Decision {

        /** The review's note: the reason alone, since the review page already names the repository's harness. */
        public String note() {
            return "Harness review refused: " + reason;
        }

        /** What the attention row shows: the harness and the reason, so a spent pool names the pool. */
        public String attentionReason() {
            return harness + ": " + reason;
        }
    }

    /** A {@code PrepareHarnessReview} was sent; the run is dispatched once the worker answers (step 6). */
    public record Preparing(String harness) implements Decision {

        /** The review's note while the worker prepares its inputs. */
        public String note() {
            throw new UnsupportedOperationException("not built yet");
        }
    }

    /** A prepared review whose round ended before its launch: nothing is dispatched and nothing is refused. */
    public record Dropped(String why) implements Decision {
    }

    /** The repository a harness review runs for and the harness it runs on. */
    private record OnHarness(UUID repositoryId, ReviewHarness harness) {
    }

    /** A same-repository first review ready to dispatch: where it runs, what it reads, and the round it answers. */
    private record Prepared(OnHarness onHarness, FixTargets.PushTarget target, int round) {
    }

    public Decision start(IntegrationEvent.ContextAssembled event) {
        Optional<OnHarness> onHarness = onHarness(event.reviewId());
        if (onHarness.isEmpty()) {
            return new NotHarness();
        }
        // Re-reviews stay on the API in step 5: step 6 brings the prior findings a harness re-review needs.
        if (projection.priorRunFor(event.reviewId()).isPresent()) {
            return new NotHarness();
        }
        String harness = onHarness.orElseThrow().harness().harness();
        Optional<FixTargets.PushTarget> target = targets.forReview(event.reviewId());
        // Null is unknown, and unknown is refused like a fork: only a known same-repository pull request runs.
        if (!Boolean.FALSE.equals(target.map(FixTargets.PushTarget::fromFork).orElse(null))) {
            return new Refused(harness, FORK_REFUSED);
        }
        // Fail-closed: the ledger's read answers run 1 on a failed read, and a run bound to a guessed round is either
        // refused at admission or dropped as stale after it was paid for (review of PR #368, code-quality/M-1).
        int round = runs.roundOrUnknown(event.reviewId());
        if (round == ReviewRuns.UNKNOWN_RUN) {
            return new Refused(harness, ROUND_UNKNOWN);
        }
        return startedOrRefused(new Prepared(onHarness.orElseThrow(), target.orElseThrow(), round), event);
    }

    /**
     * A failure while composing the run's request refuses the review: thrown, it failed the saga's message and left the
     * review in "reviewing" with no run and no attention row (live on spire-test #93). The dispatch stays outside this
     * guard: once it may have published the command a paid run may be executing, and a terminal refusal would make its
     * answer arrive at a failed review and be dropped (review of PR #368, code-quality/M-2). A dispatch that throws fails
     * the message as before, and the review stays open for the run's answer.
     */
    private Decision startedOrRefused(Prepared prepared, IntegrationEvent.ContextAssembled event) {
        String harness = prepared.onHarness().harness().harness();
        ReviewRunRequest request;
        try {
            request = request(prepared, event);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Harness review start failed for %s; the review is refused", event.reviewId());
            return new Refused(harness, START_FAILED);
        }
        return dispatched(request, harness);
    }

    private Optional<OnHarness> onHarness(String reviewId) {
        return projection.repositoryIdOf(reviewId).flatMap(id -> repositories.reviewHarness(id)
                .map(harness -> new OnHarness(id, harness)));
    }

    private ReviewRunRequest request(Prepared prepared, IntegrationEvent.ContextAssembled event) {
        ReviewHarness harness = prepared.onHarness().harness();
        return new ReviewRunRequest(prepared.onHarness().repositoryId(), binding(event, prepared.round()),
                new RunFunding.Choice(harness.harness(), harness.model(), harness.effort(), harness.subscription()),
                prompt(event, prepared.target()), dev.codespire.contract.review.HarnessReviewInputs.NONE);
    }

    private Decision dispatched(ReviewRunRequest request, String harness) {
        return switch (dispatch.dispatch(request)) {
            case ReviewRunDispatch.Dispatched sent -> new Started(sent.runId(), harness);
            case ReviewRunDispatch.AlreadyQueued queued -> new Started(queued.runId(), harness);
            // The queued row exists; a launch that never arrived surfaces as REVIEW_STUCK, not as a refusal.
            case ReviewRunDispatch.Uncertain uncertain -> new Started(uncertain.runId(), harness);
            case ReviewRunDispatch.Refused refused -> new Refused(harness, refused.reason());
        };
    }

    /** The review call this run answers: the current round's slot, so its one charge lands where an API call's would. */
    private static ReviewRunBinding binding(IntegrationEvent.ContextAssembled event, int round) {
        return new ReviewRunBinding(event.reviewId(), ReviewRunBinding.Kind.REVIEW, event.prId(), event.commit(),
                CallRefs.reviewSlot(event.commit(), round), round);
    }

    private String prompt(IntegrationEvent.ContextAssembled event, FixTargets.PushTarget target) {
        // Null means no customized prompt: the API path leaves the default to the worker, a run gets it here.
        PromptTemplate review = Optional.ofNullable(
                        promptTemplates.forKind(PromptKind.REVIEW, new RepoRef(target.workspace(), target.slug())))
                .orElseGet(() -> PromptCatalog.defaultTemplate(PromptKind.REVIEW));
        return HarnessReviewPrompt.compose(review, new HarnessReviewPrompt.ReviewTarget(target.workspace(),
                target.slug(), event.prId(), event.commit(), target.destBranch()));
    }
}
