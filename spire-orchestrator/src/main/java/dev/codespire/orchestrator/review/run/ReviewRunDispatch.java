package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.factory.FactoryCloneUrls;
import dev.codespire.orchestrator.factory.FactoryConfig;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.factory.RunCredentials;
import dev.codespire.orchestrator.factory.RunFunding;
import dev.codespire.orchestrator.factory.RunLaunch;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Funds, records and sends one harness review run (harness reviews, step 4). It funds through {@link RunFunding}, writes
 * the queued review row and launches an {@code ExecuteReviewRun} on the review lane. No caller in this step: step 5
 * dispatches the first reviews.
 *
 * <p>The pull request's branch and head come from the review's own row, and the clone credential is the REVIEWER
 * account's: a review reads what the reviewer can read, and the factory account is never involved.
 */
@ApplicationScoped
public class ReviewRunDispatch {

    @Inject
    RunFunding funding;

    @Inject
    ReviewRunAdmission admission;

    @Inject
    RunLaunch launch;

    @Inject
    RepositoryAccounts accounts;

    @Inject
    FixTargets targets;

    @Inject
    RunCredentials credentials;

    @Inject
    FactoryConfig config;

    /** What became of one dispatch. */
    public sealed interface Outcome permits Dispatched, AlreadyQueued, Uncertain, Refused {
    }

    /** The broker acknowledged the command. */
    public record Dispatched(String runId) implements Outcome {
    }

    /** The same review call's run is already queued: its deterministic id matched a row, so nothing was sent. */
    public record AlreadyQueued(String runId) implements Outcome {
    }

    /** Whether the command reached the broker is unknown, so nothing is retried until somebody knows. */
    public record Uncertain(String runId) implements Outcome {
    }

    /** Refused before a row was written or a token moved, with the reason funding gave. */
    public record Refused(String reason) implements Outcome {
    }

    /**
     * What the dispatch read before it funds anything: a refusal here costs nothing.
     *
     * @param targetBranch the pull request's destination branch, which the run's init keeps under refs/spire-base for
     *                     the agent to diff against (step 5)
     */
    private record Target(RepoRef repo, ScmType type, String sourceBranch, String targetBranch, ScmProvider reviewer) {
    }

    public Outcome dispatch(ReviewRunRequest request) {
        return target(request).map(target -> fundAndAdmit(request, target))
                .orElseGet(() -> new Refused("review_run_target_unavailable"));
    }

    private Outcome fundAndAdmit(ReviewRunRequest request, Target target) {
        RunFunding.Funded funded;
        try {
            funded = funding.fund(request.choice());
        } catch (IllegalStateException refusal) {
            return new Refused(refusal.getMessage());
        }
        String runId = ReviewRunIds.of(target.repo(), target.type(), request.binding());
        RunCommand.ExecuteReviewRun command = command(runId, request, target, funded);
        // Admitted under the lock a close or supersede takes, so a cancellation during funding refuses this row.
        return switch (admission.admit(row(command, request, target, funded), request.repositoryId())) {
            case CANCELLED -> new Refused("review_run_cancelled");
            case ALREADY_QUEUED -> new AlreadyQueued(runId);
            case ADMITTED -> launched(runId, command);
        };
    }

    private Outcome launched(String runId, RunCommand.ExecuteReviewRun command) {
        return switch (launch.launch(command)) {
            case RunLaunch.Dispatched ignored -> new Dispatched(runId);
            // Both are recorded on the row by the launch; a definite miss is re-armable by the identical retry.
            case RunLaunch.DefiniteMiss ignored -> new Refused("dispatch_failed");
            case RunLaunch.Uncertain ignored -> new Uncertain(runId);
        };
    }

    /**
     * The pull request as the review row recorded it and the reviewer account, or empty when either is missing, the
     * row names no target branch (the agent would have nothing to diff against), or the reviewer has no login a clone
     * credential could carry.
     */
    private Optional<Target> target(ReviewRunRequest request) {
        return targets.forReview(request.binding().reviewId())
                .filter(found -> found.destBranch() != null && !found.destBranch().isBlank())
                .flatMap(found -> accounts
                        .resolve(request.repositoryId(), ProviderRole.REVIEWER)
                        .filter(account -> login(account) != null)
                        .flatMap(reviewer -> ScmType.fromProviderType(found.providerType()).map(type -> new Target(
                                new RepoRef(found.workspace(), found.slug()), type, found.sourceBranch(),
                                found.destBranch(), reviewer))));
    }

    private RunCommand.ExecuteReviewRun command(String runId, ReviewRunRequest request, Target target,
                                                RunFunding.Funded funded) {
        RunFunding.Choice choice = request.choice();
        ReviewRunBinding binding = request.binding();
        ScmProvider reviewer = target.reviewer();
        RunCommand.ExecuteRun execution = new RunCommand.ExecuteRun(runId, target.repo(),
                FactoryCloneUrls.cloneUrl(target.type(), reviewer.baseUrl(), target.repo()), target.sourceBranch(),
                binding.commit(), target.sourceBranch(), request.prompt(), choice.harness(), choice.model(),
                funded.image(), List.of(), config.reviewWallClockSeconds(),
                credentials.packScm(runId, login(reviewer), reviewer.secret()),
                credentials.packHarness(runId, funded.handedOver()), false, target.targetBranch())
                .atEffort(choice.effort());
        return new RunCommand.ExecuteReviewRun(runId, choice.subscription() ? execution.paidBySignIn() : execution, binding);
    }

    private static FactoryRunProjection.QueuedRun row(RunCommand.ExecuteReviewRun command, ReviewRunRequest request,
                                                      Target target, RunFunding.Funded funded) {
        RunCommand.ExecuteRun execution = command.execution();
        FactoryRunProjection.QueuedRun row = new FactoryRunProjection.QueuedRun(command.runId(), execution.harness(),
                execution.model(), execution.baseBranch(), execution.baseCommit(), execution.branch(),
                login(target.reviewer()), funded.member().id()).asReviewFor(request.binding());
        return request.choice().subscription() ? row.paidBySubscription() : row;
    }

    /** The reviewer's login for the clone credential: its observed handle, else the configured user name. */
    private static String login(ScmProvider account) {
        if (account.botUsername() != null && !account.botUsername().isBlank()) return account.botUsername();
        if (account.authUsername() != null && !account.authUsername().isBlank()) return account.authUsername();
        return null;
    }
}
