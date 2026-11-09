package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import dev.codespire.harness.FailureCause;
import dev.codespire.runtime.EgressAttempt;
import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.ModelProxyReport;
import dev.codespire.runtime.RunHandle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a unit's model proxy saw decides why a failed run failed (harness reviews step 3): a 401 on a model call is the
 * vendor refusing the credential, which marks the pool member; an undeclared path is blocked egress; a 403 is neither.
 */
class RunLauncherModelProxyTest {

    /**
     * Answers {@link #modelProxyOf} with the report a test sets. Overridden on purpose: the interface's default returns
     * {@link ModelProxyReport#NONE}, so a fake that left it alone would make every test below pass with the launcher
     * never reading the report (the CLAUDE.md saga-fake gotcha).
     */
    private static class ProxiedRuntime extends RunLauncherTest.FakeRuntime {
        ModelProxyReport report = ModelProxyReport.NONE;
        int reads;
        /** Images this runtime holds; describing any other throws, as a failed pull does. */
        final java.util.Set<String> images = new java.util.HashSet<>();
        final List<String> described = new java.util.ArrayList<>();
        int created;

        @Override
        public ModelProxyReport modelProxyOf(RunHandle handle) {
            reads++;
            return report;
        }

        @Override
        public dev.codespire.runtime.ImageDescription describeImage(String image) {
            described.add(image);
            if (!images.contains(image)) throw new IllegalStateException("TEST pull access denied for " + image);
            return new dev.codespire.runtime.ImageDescription("TEST-pinned", Map.of());
        }

        @Override
        public RunHandle create(dev.codespire.runtime.RunUnitSpec spec) {
            created++;
            return super.create(spec);
        }
    }

    private static final String PROXY_IMAGE = "TEST-model-proxy:missing";

    /** A unit as the builder makes it, with or without a model proxy; the launcher only reads its model proxy's image. */
    private static dev.codespire.runtime.RunUnitSpec unit(boolean proxied) {
        dev.codespire.runtime.ContainerSpec init = new dev.codespire.runtime.ContainerSpec("TEST-publisher",
                List.of("spire-clone"), Map.of(), List.of(dev.codespire.runtime.Mount.writable("workspace", "/workspace")));
        dev.codespire.runtime.ContainerSpec agent = new dev.codespire.runtime.ContainerSpec("TEST-agent", List.of("fake"),
                Map.of(), List.of(dev.codespire.runtime.Mount.writable("workspace", "/workspace"),
                        dev.codespire.runtime.Mount.writable("handoff", "/handoff")));
        dev.codespire.runtime.ContainerSpec publisher = new dev.codespire.runtime.ContainerSpec("TEST-publisher",
                List.of("spire-publish-none"), Map.of(), List.of(dev.codespire.runtime.Mount.readOnly("handoff", "/handoff")));
        dev.codespire.runtime.RunUnitSpec unit = new dev.codespire.runtime.RunUnitSpec(command().runId(), init, agent,
                publisher, dev.codespire.runtime.EnterpriseEnvironment.NONE,
                new dev.codespire.runtime.EgressPolicy(dev.codespire.runtime.EgressPolicy.Mode.ENFORCE,
                        java.util.Set.of("TEST-vendor.example.test")),
                1L << 30, 1_000_000_000L, 1L << 30, java.time.Duration.ofSeconds(60));
        return proxied ? unit.withModelProxy(new dev.codespire.runtime.ContainerSpec(PROXY_IMAGE, List.of(),
                Map.of("SPIRE_MODEL_SECRET", "TEST-model-key"), List.of())) : unit;
    }

    private static RunCommand.ExecuteReviewRun review() {
        return new RunCommand.ExecuteReviewRun(command().runId(), command(),
                new dev.codespire.contract.review.ReviewRunBinding("TEST-review",
                        dev.codespire.contract.review.ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot", 1),
                dev.codespire.contract.review.HarnessReviewInputs.NONE);
    }

    /** The launcher's builder answering every kind of unit with the given one. */
    private static void building(RunLauncher launcher, dev.codespire.runtime.RunUnitSpec unit) {
        launcher.builder = new RunUnitBuilder() {
            @Override
            public dev.codespire.runtime.RunUnitSpec build(RunCommand.ExecuteRun command,
                                                           dev.codespire.harness.HarnessAdapter adapter) {
                return unit;
            }

            @Override
            public dev.codespire.runtime.RunUnitSpec buildReview(RunCommand.ExecuteReviewRun review,
                                                                 dev.codespire.harness.HarnessAdapter adapter) {
                return unit;
            }
        };
    }

    /**
     * The lane, not the worker, stops on a missing model proxy image: the run fails before anything is created, as
     * IMAGE_UNAVAILABLE naming the variable an operator sets, and nothing of a unit is left behind.
     */
    @Test
    void aReviewRunWithoutTheModelProxyImageFailsAsImageUnavailable() {
        building(launcher, unit(true));

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class,
                launcher.launchReview(review(), RunObserver.IGNORING));

        assertEquals(RunFailureCause.IMAGE_UNAVAILABLE.name(), failed.cause());
        assertTrue(failed.detail().contains("SPIRE_RUN_CREDENTIAL_PROXY_IMAGE"), failed.detail());
        assertTrue(failed.detail().contains(PROXY_IMAGE), failed.detail());
        assertEquals(0, runtime.created, "nothing is created for a unit whose model proxy cannot start");
    }

    /** With the image held, the same review unit is created as usual. */
    @Test
    void aReviewRunWithTheModelProxyImageIsCreated() {
        runtime.images.add(PROXY_IMAGE);
        building(launcher, unit(true));

        launcher.launchReview(review(), RunObserver.IGNORING);

        assertEquals(List.of(PROXY_IMAGE), runtime.described);
        assertEquals(1, runtime.created);
    }

    /** A unit with no model proxy (every factory build under REVIEWS) is never held up by the model proxy's image. */
    @Test
    void aUnitWithoutAModelProxyIsNotChecked() {
        building(launcher, unit(false));

        launcher.launch(command(), RunObserver.IGNORING);

        assertEquals(List.of(), runtime.described);
        assertEquals(1, runtime.created);
    }

    /** What the model proxy logs for every request it refused: a fixed token, never the target. */
    private static final String UNDECLARED = "<refused>";

    private static final String REFUSED_DETAIL =
            "the model proxy refused a request the harness does not declare (" + UNDECLARED + ")";

    private final ProxiedRuntime runtime = new ProxiedRuntime();

    private final RunLauncher launcher = launcher(runtime);

    private static RunLauncher launcher(ProxiedRuntime runtime) {
        return RunLauncherTest.launcher(runtime, new RunLauncherTest.FakeAdapter());
    }

    private static RunCommand.ExecuteRun command() {
        return RunLauncherTest.commandWithNoCredentials();
    }

    private RunResult.RunFailed failed() {
        return assertInstanceOf(RunResult.RunFailed.class, launcher.launch(command(), RunObserver.IGNORING));
    }

    @Test
    void aRejectedModelCallFailsAsCredentialRejected() {
        runtime.report = new ModelProxyReport(2, 1, List.of());
        runtime.finalization = Finalization.salvaged(1, "exited 1");

        RunResult.RunFailed failed = failed();

        assertEquals(RunFailureCause.CREDENTIAL_REJECTED.name(), failed.cause());
        assertEquals(RunFailureCause.CREDENTIAL_REJECTED, RunFailureCause.of(failed.cause()));
        assertEquals(1, runtime.reads);
    }

    /**
     * A request that is not a rejection (a 403 among them: a challenge or an entitlement answer, which the report counts
     * as a request and nothing more) marks nothing, and the run keeps the harness's own cause and detail, untouched.
     */
    @Test
    void aRequestThatIsNotARejectionMarksNothing() {
        runtime.report = new ModelProxyReport(1, 0, List.of());
        runtime.finalization = Finalization.salvaged(1, "exited 1");

        RunResult.RunFailed failed = failed();

        assertNotEquals(RunFailureCause.CREDENTIAL_REJECTED.name(), failed.cause());
        assertEquals(FailureCause.HARNESS_EXIT_NONZERO.name(), failed.cause(), "the adapter's own cause");
        assertEquals("exit 1", failed.detail(), "the adapter's own detail, with nothing of the proxy's put on it");
        assertEquals(1, runtime.reads, "guards the guard: the report was read and said nothing");
    }

    @Test
    void aRefusedModelPathFailsAsBlockedEgress() {
        runtime.report = new ModelProxyReport(2, 0, List.of(UNDECLARED));
        runtime.finalization = Finalization.salvaged(1, "exited 1");

        RunResult.RunFailed failed = failed();

        assertEquals(RunFailureCause.BLOCKED_EGRESS.name(), failed.cause());
        assertTrue(failed.detail().contains(REFUSED_DETAIL), failed.detail());
    }

    /** A refused credential is the more exact story than a refused host, so it wins over the egress proxy's. */
    @Test
    void aRejectedCredentialOutranksARefusedHost() {
        runtime.report = new ModelProxyReport(1, 1, List.of());
        runtime.egress = List.of(new EgressAttempt("TEST-undeclared.example.test", 443, false));
        runtime.finalization = Finalization.salvaged(1, "exited 1");

        assertEquals(RunFailureCause.CREDENTIAL_REJECTED.name(), failed().cause());
    }

    /** Nobody observed the exit, but the model proxy's record is still read before the unit is preserved. */
    @Test
    void theReportIsReadWhenSalvageFails() {
        runtime.report = new ModelProxyReport(1, 1, List.of());
        runtime.salvageFails = new IllegalStateException("TEST daemon went away");

        RunResult.RunFailed failed = failed();

        assertEquals(RunFailureCause.CREDENTIAL_REJECTED.name(), failed.cause());
        assertEquals(1, runtime.reads);
    }

    /** An overrun is unobserved too, through the salvaged path's other branch. */
    @Test
    void anOverrunAfterARefusedPathIsBlockedEgress() {
        runtime.report = new ModelProxyReport(1, 0, List.of(UNDECLARED));
        runtime.finalization = Finalization.overran("the wall clock ran out");

        RunResult.RunFailed failed = failed();

        assertEquals(RunFailureCause.BLOCKED_EGRESS.name(), failed.cause());
        assertTrue(failed.detail().contains(REFUSED_DETAIL), failed.detail());
    }

    @Test
    void aCleanReportChangesNothing() {
        runtime.report = new ModelProxyReport(3, 0, List.of());
        runtime.finalization = Finalization.salvaged(1, "exited 1");

        assertEquals(FailureCause.HARNESS_EXIT_NONZERO.name(), failed().cause());
        assertEquals(1, runtime.reads);
    }

    /** A run that succeeded keeps its outcome whatever the proxy saw: only a failed run is explained by it. */
    @Test
    void aFinishedRunIsNotRewrittenByARejection() {
        runtime.report = new ModelProxyReport(2, 1, List.of());

        assertInstanceOf(RunResult.RunFinished.class, launcher.launch(command(), RunObserver.IGNORING));
    }

    /** A report that cannot be read is no report: the run is decided without it. */
    @Test
    void anUnreadableReportIsNone() {
        ProxiedRuntime throwing = new ProxiedRuntime() {
            @Override
            public ModelProxyReport modelProxyOf(RunHandle handle) {
                throw new IllegalStateException("TEST log gone");
            }
        };
        throwing.finalization = Finalization.salvaged(1, "exited 1");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class,
                launcher(throwing).launch(command(), RunObserver.IGNORING));

        assertEquals(FailureCause.HARNESS_EXIT_NONZERO.name(), failed.cause());
    }
}
