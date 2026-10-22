package dev.codespire.orchestrator.work;

import dev.codespire.contract.port.PullRequestLander.AllowedMethods;
import dev.codespire.contract.port.PullRequestLander.Checks;
import dev.codespire.contract.port.PullRequestLander.LandState;
import dev.codespire.contract.port.PullRequestLander.MergeMethod;
import java.util.Objects;
import java.util.Set;

/**
 * What one reading of an approved pull request means for its land (M4 land, spec §7.3 as amended by A4 and
 * A5). Pure: the sweep reads, this decides, the sweep acts. The order is the decision: a moved head outranks
 * everything, because nothing read about another head says anything about the approved one.
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

    /** The states in which GitHub merges when asked; anything else is waited for or handed. */
    private static final Set<String> MERGEABLE = Set.of("clean", "unstable", "has_hooks");

    static Next checking(LandState pr, String approvedHead, AllowedMethods allowed, MergeMethod method) {
        if (pr.merged()) return new Next.MergedFound(pr.mergeCommitSha(), pr.mergedBy());
        if (!Objects.equals(approvedHead, pr.headSha())) return new Next.Hand("land_head_moved");
        // Read from the repository's allow_*_merge flags, never parsed from a refusal (A5).
        if (allowed.checked() && !allowed.allowed().contains(method)) return new Next.Hand("land_merge_method_not_allowed");
        Checks checks = pr.checks().state();
        if (checks == Checks.NOT_CODE) return new Next.Hand("land_checks_not_code");
        // A failing non-required check is fixed too: the operator asked for failed checks to be fixed (§7.4).
        if (checks == Checks.FAILED) return new Next.Fix("land_checks_failed");
        String state = pr.mergeableState();
        if ("dirty".equals(state)) return new Next.Fix("land_conflict");
        if ("behind".equals(state)) return new Next.Fix("land_branch_behind");
        if (pr.mergeable() == null || state == null) return new Next.Wait();
        // Only clean says every required check passed; a pending check in any other state may be required (A4).
        if (checks == Checks.PENDING && !"clean".equals(state)) return new Next.Wait();
        if ("blocked".equals(state)) return new Next.Hand("land_blocked");
        if ("draft".equals(state)) return new Next.Hand("land_pr_draft");
        if (MERGEABLE.contains(state)) return new Next.Merge();
        // unknown is GitHub still computing (A4); a state it adds later is treated the same and never merged on.
        return new Next.Wait();
    }
}
