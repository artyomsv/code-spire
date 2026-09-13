package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunIds;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.port.PullRequestSink;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ScmProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Proposes what a finished run pushed: the step that turns a branch on the remote into a pull
 * request a human can read, argue with and merge.
 *
 * <p><b>This is the caller M2 shipped without.</b> The {@link PullRequestSink} port, its three
 * forge adapters and {@link FactoryPullRequestBody} all landed in PR #119; nothing invoked them, so
 * every run ended at a pushed branch and the milestone's own goal — "a run can end at a pull
 * request rather than at a branch" — was true of the parts and false of the product. The first live
 * run proved it: correct work, a branch, and no proposal.
 *
 * <p><b>Only a BUILD run proposes.</b> A fix run pushes onto the pull request's own source branch
 * (ADR-040), so its change is already proposed and already under review; asking a forge to open a
 * second one would put the same commits in front of the same reviewer twice.
 *
 * <p><b>It never fails the run.</b> By the time this is reached the work is on the remote, which is
 * what {@code succeeded} means and what an operator can act on. A proposal that could not be made
 * afterwards is recorded beside the run — see {@link FactoryRunProjection#pullRequestFailed} — and
 * changes nothing about the run's status. The alternative, throwing, would nack the result record,
 * redeliver it, and re-run every consumer that already applied it.
 */
@ApplicationScoped
public class FactoryPullRequests {

    private static final Logger LOG = Logger.getLogger(FactoryPullRequests.class);

    @Inject
    FactoryRunProjection projection;

    @Inject
    MachineAccounts machineAccounts;

    @Inject
    ProviderClients clients;

    /**
     * Open a pull request for a run that pushed, if that is what this run is for.
     *
     * <p>Called after the projection has applied the result, so the row this reads is the finished
     * one: a plan read before the apply would see the queued row and its null {@code pushed_ref}.
     */
    public void propose(RunResult result) {
        if (!(result instanceof RunResult.RunFinished finished) || finished.pushedRef() == null) {
            // Nothing on the remote to propose. A refused gate and a failed run both land here.
            return;
        }
        String runId = finished.runId();
        Optional<FactoryRunProjection.PullRequestPlan> found = projection.pullRequestPlanOf(runId);
        if (found.isEmpty()) {
            // The row is written before dispatch, so its absence is not an ordinary state. Said
            // rather than silently skipped, because the branch IS on the remote.
            LOG.warnf("run %s pushed %s but has no row to propose from", runId, finished.pushedRef());
            return;
        }
        FactoryRunProjection.PullRequestPlan plan = found.get();
        if (!RunKind.BUILD.name().equals(plan.kind()) || plan.alreadyProposed()) {
            return;
        }
        try {
            open(runId, plan, finished);
        } catch (RuntimeException e) {
            // Every failure from here is the forge's or the account's, and the run is already a
            // success. Recorded where the runs list can show it; the message, not the stack, because
            // the stack of a 403 says nothing an operator can act on.
            LOG.warnf(e, "run %s pushed but could not open a pull request", runId);
            projection.pullRequestFailed(runId, reason(e));
        }
    }

    private void open(String runId, FactoryRunProjection.PullRequestPlan plan,
                      RunResult.RunFinished finished) {
        RunIds.Parsed parsed = RunIds.parse(runId);
        RepoRef repo = new RepoRef(parsed.workspace(), parsed.slug());
        ScmProvider account = projection.repositoryIdOf(runId).flatMap(machineAccounts::resolve)
                .orElseThrow(() -> new IllegalStateException(
                        "no usable FACTORY account for " + parsed.scmType().providerType()
                                + "/" + parsed.workspace() + "; the branch is pushed and unproposed"));
        PullRequestSink sink = clients.pullRequestSink(account);
        // find-first, then open. The sink's own adapters do this too, and it is repeated here for a
        // different reason: a result record redelivered after a restart must not ask a second time.
        PullRequestRef opened = sink.findByHead(repo, plan.branch(), plan.baseBranch())
                .orElseGet(() -> sink.open(repo, new PullRequestSink.NewPullRequest(
                        plan.branch(), plan.baseBranch(),
                        FactoryPullRequestBody.title(plan.taskSummary()),
                        FactoryPullRequestBody.of(runId, plan.taskSummary(), finished.changedPaths()))));
        projection.pullRequestOpened(runId, opened.number(), opened.url());
        LOG.infof("run %s proposed as %s", runId, opened.url());
    }

    /**
     * One line for the operator, from an exception that may carry none.
     *
     * <p>{@link PullRequestSink.NothingToPropose} is the honest empty answer — the agent changed
     * nothing the base branch does not already have — and reads as a failure if it arrives as a
     * class name.
     */
    private static String reason(RuntimeException e) {
        if (e instanceof PullRequestSink.NothingToPropose) {
            return "the forge found nothing to propose: the branch holds no change the base does not";
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
