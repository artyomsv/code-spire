package dev.codespire.orchestrator.work;

import dev.codespire.contract.port.PullRequestLander.AllowedMethods;
import dev.codespire.contract.port.PullRequestLander.CheckSummary;
import dev.codespire.contract.port.PullRequestLander.Checks;
import dev.codespire.contract.port.PullRequestLander.FailedCheck;
import dev.codespire.contract.port.PullRequestLander.LandState;
import dev.codespire.contract.port.PullRequestLander.MergeMethod;
import dev.codespire.orchestrator.work.WorkLandDecision.Next;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The land decision table (M4 land, spec §7.3 as amended by A4 and A5), one row per test. Each input also
 * carries what a later row would decide, so a row moved below it is caught, not just a row deleted.
 */
class WorkLandDecisionTest {
    static final String HEAD = "b".repeat(40), MERGE = "c".repeat(40), OTHER = "d".repeat(40);
    static final AllowedMethods UNCHECKED = new AllowedMethods(false, Set.of());

    static CheckSummary checks(Checks state) {
        List<FailedCheck> failed = state == Checks.FAILED || state == Checks.NOT_CODE
                ? List.of(new FailedCheck("TEST-check", "failure", "TEST-title", "TEST-summary", List.of())) : List.of();
        return new CheckSummary(state, state == Checks.PENDING ? 0 : 1, 1, failed);
    }

    static LandState open(String head, Boolean mergeable, String mergeableState, Checks checks) {
        return new LandState("open", false, null, null, head, "TEST-branch", "main", "a".repeat(40), mergeable, mergeableState, checks(checks));
    }

    static Next decide(LandState pr) {
        return WorkLandDecision.checking(pr, HEAD, new WorkLandDecision.Methods(UNCHECKED, MergeMethod.MERGE));
    }

    @Test void aMergedPullRequestIsFoundWithItsMerger() {
        LandState merged = new LandState("closed", true, MERGE, "900123", OTHER, "TEST-branch", "main", "a".repeat(40), null, "unknown",
                checks(Checks.FAILED));
        assertEquals(new Next.MergedFound(MERGE, "900123"), decide(merged));
    }

    @Test void aClosedUnmergedPullRequestIsClosed() {
        LandState closed = new LandState("closed", false, null, null, OTHER, "TEST-branch", "main", "a".repeat(40), true, "dirty",
                checks(Checks.FAILED));
        assertEquals(new Next.Closed(), decide(closed));
    }

    @Test void aMovedHeadIsHandedBeforeAnythingElse() {
        assertEquals(new Next.Hand("land_head_moved"), WorkLandDecision.checking(open(OTHER, true, "dirty", Checks.NOT_CODE), HEAD,
                new WorkLandDecision.Methods(new AllowedMethods(true, Set.of(MergeMethod.SQUASH)), MergeMethod.MERGE)));
    }

    @Test void aForbiddenMethodIsHandedBeforeAnyMergeCall() {
        assertEquals(new Next.Hand("land_merge_method_not_allowed"), WorkLandDecision.checking(open(HEAD, true, "clean", Checks.NOT_CODE), HEAD,
                new WorkLandDecision.Methods(new AllowedMethods(true, Set.of(MergeMethod.MERGE, MergeMethod.REBASE)), MergeMethod.SQUASH)));
    }

    @Test void anUncheckedMethodListDoesNotHand() {
        assertEquals(new Next.Merge(), WorkLandDecision.checking(open(HEAD, true, "clean", Checks.PASSED), HEAD, new WorkLandDecision.Methods(UNCHECKED, MergeMethod.SQUASH)));
    }

    @Test void anAllowedMethodMerges() {
        assertEquals(new Next.Merge(), WorkLandDecision.checking(open(HEAD, true, "clean", Checks.PASSED), HEAD,
                new WorkLandDecision.Methods(new AllowedMethods(true, Set.of(MergeMethod.SQUASH)), MergeMethod.SQUASH)));
    }

    @Test void aCheckACodeChangeCannotFixIsHanded() {
        assertEquals(new Next.Hand("land_checks_not_code"), decide(open(HEAD, true, "dirty", Checks.NOT_CODE)));
    }

    @Test void unstableWithAFailedCheckFixes() {
        assertEquals(new Next.Fix("land_checks_failed"), decide(open(HEAD, true, "unstable", Checks.FAILED)));
    }

    @Test void aFailedCheckOutranksAConflict() {
        assertEquals(new Next.Fix("land_checks_failed"), decide(open(HEAD, false, "dirty", Checks.FAILED)));
    }

    @Test void aConflictFixes() {
        assertEquals(new Next.Fix("land_conflict"), decide(open(HEAD, false, "dirty", Checks.PENDING)));
    }

    @Test void aBranchBehindItsBaseFixes() {
        assertEquals(new Next.Fix("land_branch_behind"), decide(open(HEAD, true, "behind", Checks.PENDING)));
    }

    @Test void unknownMergeableStateWaits() {
        assertEquals(new Next.Wait(), decide(open(HEAD, true, "unknown", Checks.PASSED)));
    }

    @Test void aMergeabilityGitHubStillComputesWaits() {
        assertEquals(new Next.Wait(), decide(open(HEAD, null, "clean", Checks.PASSED)));
    }

    @Test void pendingChecksWaitWhenTheStateIsNotClean() {
        assertEquals(new Next.Wait(), decide(open(HEAD, true, "blocked", Checks.PENDING)));
    }

    @Test void cleanWithPendingNonRequiredChecksMerges() {
        assertEquals(new Next.Merge(), decide(open(HEAD, true, "clean", Checks.PENDING)));
    }

    @Test void blockedWithPassedChecksIsHanded() {
        assertEquals(new Next.Hand("land_blocked"), decide(open(HEAD, true, "blocked", Checks.PASSED)));
    }

    @Test void aDraftIsHanded() {
        assertEquals(new Next.Hand("land_pr_draft"), decide(open(HEAD, true, "draft", Checks.PASSED)));
    }

    @Test void unstableWithPassedChecksMerges() {
        assertEquals(new Next.Merge(), decide(open(HEAD, true, "unstable", Checks.PASSED)));
    }

    @Test void hasHooksWithPassedChecksMerges() {
        assertEquals(new Next.Merge(), decide(open(HEAD, true, "has_hooks", Checks.PASSED)));
    }

    @Test void aStateGitHubAddsLaterWaitsAndIsNeverMerged() {
        assertEquals(new Next.Wait(), decide(open(HEAD, true, "TEST-new-state", Checks.PASSED)));
    }
}
