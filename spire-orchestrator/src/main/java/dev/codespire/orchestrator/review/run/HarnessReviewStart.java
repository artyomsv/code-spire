package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Whether a review runs on a harness, and if so sends it to the review worker to prepare (harness reviews, step 6).
 *
 * <p>Decides in this order: no repository or no review harness is {@link NotHarness}; an untrusted pull request (a fork) with no pull ref, or one whose
 * provenance is unknown and has no pull ref, is {@link Refused}; a review whose round cannot be read is {@link Refused}; a review with no
 * packed reviewer credential is {@link Refused}; otherwise a {@code PrepareHarnessReview} is sent and the decision is
 * {@link Preparing}. A re-review whose prior run has open findings is prepared as a {@code RECONCILE}, any other review
 * as a {@code REVIEW}. The run is dispatched once the worker answers, by {@link HarnessReviewLaunch}. A refusal never
 * falls back to the API (operator decision 6).
 *
 * <p>The texts the saga writes for each decision are built here, so {@code ResultSaga} only routes them.
 */
@ApplicationScoped
public class HarnessReviewStart {

    /**
     * An untrusted pull request (a fork, or unknown provenance) is reviewed from the forge's own pull ref, and this
     * forge has none (Bitbucket): the fork's branch lives in another repository and is never cloned.
     */
    static final String FORK_REFUSED = "fork_pull_request_has_no_pull_ref";

    /** The review's round could not be read, so no run is bound to one; a re-run asks again. */
    static final String ROUND_UNKNOWN = "review_round_unknown";

    /** Composing the run's prompt or request threw; the log has the cause. */
    static final String START_FAILED = "harness_review_start_failed";

    /** The worker cannot read the pull request without the reviewer's account (plan call 12). */
    static final String CREDENTIAL_UNAVAILABLE = "reviewer_credential_unavailable";

    @Inject
    RepositoryRegistry repositories;

    @Inject
    ReviewProjection projection;

    @Inject
    FixTargets targets;

    /** Never called from here: the launch dispatches (HarnessReviewStartTest keeps a fake to prove the start never does). */
    @Inject
    ReviewRunDispatch dispatch;

    @Inject
    ReviewRuns runs;

    /** The reviewer's packed forge credential a {@code PrepareHarnessReview} carries. */
    @Inject
    WorkerCredentials credentials;

    /** Where a {@code PrepareHarnessReview} is sent. */
    @Inject
    CommandsEmitter commands;

    /** What became of a review's start or of its launch. */
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
            return "Preparing the review on " + harness;
        }
    }

    /** A prepared review whose round ended before its launch: nothing is dispatched and nothing is refused. */
    public record Dropped(String why) implements Decision {
    }

    public Decision start(IntegrationEvent.ContextAssembled event) {
        Optional<String> harness = projection.repositoryIdOf(event.reviewId())
                .flatMap(id -> repositories.reviewHarness(id)).map(found -> found.harness());
        if (harness.isEmpty()) {
            return new NotHarness();
        }
        if (targets.isUntrustedReviewWithoutPullRef(event.reviewId())) {
            return new Refused(harness.orElseThrow(), FORK_REFUSED);
        }
        // Fail-closed: the ledger's read answers run 1 on a failed read, and a run bound to a guessed round is either
        // refused at admission or dropped as stale after it was paid for (review of PR #368, code-quality/M-1).
        int round = runs.roundOrUnknown(event.reviewId());
        if (round == ReviewRuns.UNKNOWN_RUN) {
            return new Refused(harness.orElseThrow(), ROUND_UNKNOWN);
        }
        return prepare(event, harness.orElseThrow(), round);
    }

    /**
     * Sends the review to the worker to prepare. A missing reviewer credential refuses it with attention, never leaves it
     * "reviewing" as a note-only skip would (plan call 12). The prior run is read here, once: the worker numbers and
     * persists it, so the verdict ids stay those the agent read (plan call 3).
     */
    private Decision prepare(IntegrationEvent.ContextAssembled event, String harness, int round) {
        // The API path skips reconcile when nothing is open (plan call 6), so an empty reconcile is never charged.
        PriorRun prior = projection.priorRunFor(event.reviewId()).filter(run -> !run.findings().isEmpty()).orElse(null);
        ReviewRunBinding.Kind kind = prior == null ? ReviewRunBinding.Kind.REVIEW : ReviewRunBinding.Kind.RECONCILE;
        Optional<String> credential = credentials.packForReview(event.reviewId());
        if (credential.isEmpty()) {
            return new Refused(harness, CREDENTIAL_UNAVAILABLE);
        }
        commands.emit(new ActionCommand.PrepareHarnessReview(event.reviewId(), ReviewIds.parse(event.reviewId()).repo(),
                event.prId(), event.commit(), event.contextRef(), credential.orElseThrow(), prior, round, kind));
        return new Preparing(harness);
    }
}
