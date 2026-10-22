package dev.codespire.orchestrator.work;

import dev.codespire.contract.port.PullRequestLander.AllowedMethods;
import dev.codespire.contract.port.PullRequestLander.LandState;
import dev.codespire.contract.port.PullRequestLander.MergeMethod;

/**
 * What one reading of an approved pull request means for its land (M4 land, spec §7.3 as amended by A4 and
 * A5). Pure: the sweep reads, this decides, the sweep acts. The order is the decision.
 */
final class WorkLandDecision {
    private WorkLandDecision() {}

    sealed interface Next {
        record Merge() implements Next {}
        record Wait() implements Next {}
        record Hand(String reason) implements Next {}
        record Fix(String reason) implements Next {}
        record MergedFound(String sha, String mergedBy) implements Next {}
        record Closed() implements Next {}
    }

    static Next checking(LandState pr, String approvedHead, AllowedMethods allowed, MergeMethod method) {
        throw new UnsupportedOperationException("RED");
    }
}
