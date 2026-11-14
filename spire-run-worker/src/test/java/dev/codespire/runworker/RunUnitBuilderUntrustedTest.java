package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.harness.codex.CodexAdapter;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.RunUnitSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A fork's review (harness reviews step 8) as the builder places it: proxied only, its head read from the pull ref. */
class RunUnitBuilderUntrustedTest extends ModelProxyUnitFixture {

    private static final String PULL_REF = "refs/pull/7/head";

    /** A fork's review (step 8): cloned onto its local review branch, its head read from the pull ref. */
    private RunCommand.ExecuteUntrustedReviewRun fork(RunCommand.ExecuteRun execution,
                                                      dev.codespire.contract.review.ReviewRunBinding.Kind kind) {
        RunCommand.ExecuteRun onLocalBranch = new RunCommand.ExecuteRun(RUN_ID, execution.repo(), execution.remoteUri(),
                execution.baseBranch(), execution.baseCommit(), dev.codespire.contract.review.PullRefs.localBranch(7),
                execution.prompt(), execution.harness(), execution.model(), execution.agentImage(),
                execution.protectedPaths(), execution.maxWallClockSeconds(), execution.scmCredential(),
                execution.harnessCredential(), execution.existingBranch(), execution.protectedBranch(),
                execution.reasoningEffort(), execution.harnessSignIn(), null, null);
        return new RunCommand.ExecuteUntrustedReviewRun(RUN_ID, onLocalBranch,
                new dev.codespire.contract.review.ReviewRunBinding("TEST-review", kind, 7, "a".repeat(40), "TEST-slot", 1),
                dev.codespire.contract.review.HarnessReviewInputs.NONE, PULL_REF);
    }

    private RunCommand.ExecuteUntrustedReviewRun fork(RunCommand.ExecuteRun execution) {
        return fork(execution, dev.codespire.contract.review.ReviewRunBinding.Kind.REVIEW);
    }

    @Test
    void thePullRefReachesInit() {
        RunUnitSpec unit = proxying(CredentialProxyMode.REVIEWS, EgressPolicy.Mode.REPORT)
                .buildUntrustedReview(fork(reviewExecution(HARNESS_KEY)), new CodexAdapter());

        assertEquals(PULL_REF, unit.init().environment().get(RunUnitBuilder.FETCH_REF));
        assertEquals("SPIRE_FETCH_REF", RunUnitBuilder.FETCH_REF);
        assertEquals("main", unit.init().environment().get(RunUnitBuilder.FETCH_BRANCH), "the target branch is kept too");
        assertFalse(unit.agent().environment().containsKey(RunUnitBuilder.FETCH_REF), "only init is told the ref");
    }

    @Test
    void aTrustedReviewSetsNoFetchRef() {
        RunUnitSpec unit = proxying(CredentialProxyMode.REVIEWS, EgressPolicy.Mode.REPORT)
                .buildReview(review(reviewExecution(HARNESS_KEY)), new CodexAdapter());

        assertFalse(unit.init().environment().containsKey(RunUnitBuilder.FETCH_REF));
    }

    @Test
    void anUntrustedReviewIsProxiedOnCodex() {
        RecordingCodex keyArm = new RecordingCodex();
        RunUnitSpec onKey = proxying(CredentialProxyMode.REVIEWS, EgressPolicy.Mode.ENFORCE)
                .buildUntrustedReview(fork(reviewExecution(HARNESS_KEY)), keyArm);
        assertEquals(HARNESS_KEY, onKey.environmentFor(onKey.modelProxy()).get("SPIRE_MODEL_SECRET"));
        assertTheAgentNeverHolds(HARNESS_KEY, onKey, keyArm);

        String file = seatFile();
        RecordingCodex seatArm = new RecordingCodex();
        RunUnitSpec onSeat = proxying(CredentialProxyMode.REVIEWS, EgressPolicy.Mode.ENFORCE)
                .buildUntrustedReview(fork(reviewExecution(file).paidBySignIn()), seatArm);
        assertEquals(SEAT_ACCESS, onSeat.environmentFor(onSeat.modelProxy()).get("SPIRE_MODEL_SECRET"));
        assertTheAgentNeverHolds(SEAT_ACCESS, onSeat, seatArm);
        assertTheAgentNeverHolds(SEAT_ACCOUNT, onSeat, seatArm);
    }

    @Test
    void anUntrustedReviewIsProxiedOnClaudeCode() {
        RunUnitSpec unit = proxying(CredentialProxyMode.REVIEWS, EgressPolicy.Mode.ENFORCE)
                .buildUntrustedReview(fork(reviewExecution(HARNESS_KEY)),
                        new dev.codespire.harness.claudecode.ClaudeCodeAdapter());

        assertTrue(unit.modelProxy() != null, "a fork review on Claude Code has a model proxy");
        assertEquals(HARNESS_KEY, unit.environmentFor(unit.modelProxy()).get("SPIRE_MODEL_SECRET"));
        assertEquals("https://api.anthropic.com", unit.environmentFor(unit.modelProxy()).get("SPIRE_MODEL_UPSTREAM"));
        unit.environmentFor(unit.agent()).forEach((name, value) ->
                assertFalse(value.contains(HARNESS_KEY), "the agent's " + name + " holds the credential"));
        unit.agent().argv().forEach(arg -> assertFalse(arg.contains(HARNESS_KEY)));
    }

    @Test
    void anUntrustedReconcileAndFollowupAreProxied() {
        for (dev.codespire.contract.review.ReviewRunBinding.Kind kind : List.of(
                dev.codespire.contract.review.ReviewRunBinding.Kind.RECONCILE,
                dev.codespire.contract.review.ReviewRunBinding.Kind.FOLLOWUP)) {
            RecordingCodex arm = new RecordingCodex();
            RunUnitSpec unit = proxying(CredentialProxyMode.REVIEWS, EgressPolicy.Mode.ENFORCE)
                    .buildUntrustedReview(fork(reviewExecution(HARNESS_KEY), kind), arm);

            assertTrue(unit.modelProxy() != null, kind.name());
            assertEquals(PULL_REF, unit.init().environment().get(RunUnitBuilder.FETCH_REF), kind.name());
            assertTheAgentNeverHolds(HARNESS_KEY, unit, arm);
        }
    }

    /**
     * Egress in report mode still routes the model proxy through the egress proxy, so a fork review is allowed there.
     */
    @Test
    void anUntrustedReviewIsProxiedWithEgressInReportMode() {
        RunUnitSpec unit = proxying(CredentialProxyMode.REVIEWS, EgressPolicy.Mode.REPORT)
                .buildUntrustedReview(fork(reviewExecution(HARNESS_KEY)), new CodexAdapter());

        assertTrue(unit.modelProxy() != null);
    }

    @Test
    void anUntrustedReviewIsRefusedWhenTheModeIsOff() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
                proxying(CredentialProxyMode.OFF, EgressPolicy.Mode.ENFORCE)
                        .buildUntrustedReview(fork(reviewExecution(HARNESS_KEY)), new CodexAdapter()));

        assertEquals(RunUnitBuilder.FORK_PROXY_REQUIRED, refused.getMessage());
    }

    @Test
    void anUntrustedReviewIsRefusedWhenEgressIsOff() {
        for (CredentialProxyMode mode : List.of(CredentialProxyMode.REVIEWS, CredentialProxyMode.ALL)) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
                    proxying(mode, EgressPolicy.Mode.OFF)
                            .buildUntrustedReview(fork(reviewExecution(HARNESS_KEY)), new CodexAdapter()), mode.name());

            assertEquals(RunUnitBuilder.FORK_PROXY_REQUIRED, refused.getMessage(), mode.name());
        }
    }

    /**
     * Claude Code would refuse an unproxied run itself, as credential_proxy_required; a fork is refused as a fork
     * first, in every mode that does not proxy reviews and with egress off.
     */
    @Test
    void anUnproxiedClaudeCodeForkIsRefusedAsAFork() {
        for (CredentialProxyMode mode : CredentialProxyMode.values()) {
            for (EgressPolicy.Mode egress : EgressPolicy.Mode.values()) {
                boolean proxied = mode != CredentialProxyMode.OFF && egress != EgressPolicy.Mode.OFF;
                if (proxied) continue;
                IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
                        proxying(mode, egress).buildUntrustedReview(fork(reviewExecution(HARNESS_KEY)),
                                new dev.codespire.harness.claudecode.ClaudeCodeAdapter()), mode + "/" + egress);

                assertEquals(RunUnitBuilder.FORK_PROXY_REQUIRED, refused.getMessage(), mode + "/" + egress);
            }
        }
    }

    /** An arm that declares no route (the fake adapter) would fall back to a direct credential: refused. */
    @Test
    void anUntrustedReviewIsRefusedOnAnArmWithNoRoute() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
                proxying(CredentialProxyMode.REVIEWS, EgressPolicy.Mode.ENFORCE)
                        .buildUntrustedReview(fork(reviewExecution(HARNESS_KEY)), new RunLauncherTest.FakeAdapter()));

        assertEquals(RunUnitBuilder.FORK_PROXY_REQUIRED, refused.getMessage());
    }

    /** What an operator reads in a failure must not carry the key, the sign-in file or the SCM token. */
    @Test
    void theRefusalCarriesNoCredentialText() {
        String file = seatFile();
        for (RunCommand.ExecuteRun execution : List.of(reviewExecution(HARNESS_KEY), reviewExecution(file).paidBySignIn())) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
                    proxying(CredentialProxyMode.OFF, EgressPolicy.Mode.OFF)
                            .buildUntrustedReview(fork(execution), new CodexAdapter()));

            String text = refused.toString() + refused.getMessage();
            for (String secret : List.of(HARNESS_KEY, SEAT_ACCESS, SEAT_ACCOUNT, SEAT_ID_TOKEN, SCM_TOKEN, SCM_LOGIN)) {
                assertFalse(text.contains(secret), "the refusal names a credential");
            }
            assertEquals(RunUnitBuilder.FORK_PROXY_REQUIRED, refused.getMessage());
        }
    }

    /**
     * The worker cannot depend on spire-workspace (RunWorkerRunsNoGitTest), so the init-side pattern is compared in
     * spire-arch's ForkRefPatternsAgreeTest; here the contract's pattern is pinned to the two shapes init accepts.
     */
    @Test
    void theContractPatternIsPinned() {
        assertEquals("refs/(pull|merge-requests)/[1-9][0-9]{0,17}/head", dev.codespire.contract.review.PullRefs.PATTERN);
    }
}
