package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A fork's review (harness reviews step 8) through the launcher: refused unless its unit has a model proxy. */
class RunLauncherUntrustedTest {

    private final RunLauncherModelProxyTest.ProxiedRuntime runtime = new RunLauncherModelProxyTest.ProxiedRuntime();

    private final RunLauncher launcher = RunLauncherModelProxyTest.launcher(runtime);

    /** A fork's review (step 8): its branch is the local review branch, its head is read from the pull ref. */
    private static RunCommand.ExecuteUntrustedReviewRun fork() {
        RunCommand.ExecuteRun base = RunLauncherModelProxyTest.command();
        RunCommand.ExecuteRun execution = new RunCommand.ExecuteRun(base.runId(), base.repo(), base.remoteUri(),
                base.baseBranch(), base.baseCommit(), dev.codespire.contract.review.PullRefs.localBranch(7),
                base.prompt(), base.harness(), base.model(), base.agentImage(), base.protectedPaths(),
                base.maxWallClockSeconds(), base.scmCredential(), base.harnessCredential());
        return new RunCommand.ExecuteUntrustedReviewRun(base.runId(), execution,
                new dev.codespire.contract.review.ReviewRunBinding("TEST-review",
                        dev.codespire.contract.review.ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot", 1),
                dev.codespire.contract.review.HarnessReviewInputs.NONE, "refs/pull/7/head");
    }

    /**
     * A fork's review whose unit has no model proxy (a mode, egress or an arm that does not proxy it) is refused by the
     * real builder as BAD_COMMAND before anything is created: the fork's code never runs beside a real credential. The
     * detail is the constant, which carries no credential text.
     */
    @Test
    void anUnproxiedForkFailsBadCommandBeforeAnyContainer() {
        RunLauncherModelProxyTest.building(launcher, RunLauncherModelProxyTest.directUnit());

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class,
                launcher.launchUntrustedReview(fork(), RunObserver.IGNORING));

        assertEquals("BAD_COMMAND", failed.cause());
        assertTrue(failed.detail().contains(RunUnitBuilder.FORK_PROXY_REQUIRED), failed.detail());
        assertEquals("fork_requires_credential_proxy", RunUnitBuilder.FORK_PROXY_REQUIRED);
        assertEquals(0, runtime.created, "no unit is created for a fork whose credential would sit in its agent");
        assertEquals(List.of(), runtime.described);
    }

    /** The same fork on a proxied unit is created, through the same launcher path as a trusted review. */
    @Test
    void aProxiedForkIsCreated() {
        runtime.images.add(RunLauncherModelProxyTest.PROXY_IMAGE);
        RunLauncherModelProxyTest.building(launcher, RunLauncherModelProxyTest.proxiedUnit());

        RunResult result = launcher.launchUntrustedReview(fork(), RunObserver.IGNORING);

        // No answer was handed over, so the result is the review's own missing-artifact failure, not a bare RunFinished.
        assertEquals(RunFailureCause.ARTIFACT_MISSING.name(), assertInstanceOf(RunResult.RunFailed.class, result).cause());
        assertEquals(1, runtime.created);
        assertEquals(List.of(RunLauncherModelProxyTest.PROXY_IMAGE), runtime.described);
    }
}
