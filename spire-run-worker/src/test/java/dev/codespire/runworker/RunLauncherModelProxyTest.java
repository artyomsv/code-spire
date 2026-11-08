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

        @Override
        public ModelProxyReport modelProxyOf(RunHandle handle) {
            reads++;
            return report;
        }
    }

    private static final String UNDECLARED = "/v1/files";

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
     * A 403 can be a challenge or an entitlement answer, and marking would retire a healthy seat: the report counts a
     * {@code POST /v1/responses -> 403} as a request and nothing more, and the run keeps the harness's own cause and
     * detail, untouched.
     */
    @Test
    void aForbiddenAnswerIsNotARejectedCredential() {
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
        assertTrue(failed.detail().contains("model path " + UNDECLARED + " refused"), failed.detail());
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
        assertTrue(failed.detail().contains("model path " + UNDECLARED + " refused"), failed.detail());
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
