package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.MachineAccountCredential;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.harness.HarnessAdapter;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M0 exit criteria, BOTH halves (docs/factory/ROADMAP.md). The first alone celebrates the
 * ungated path, which is the defect ADR-037 exists to close.
 *
 * <p>Real containers, a real smart-HTTP remote on the Docker network, the real publisher image
 * built from this repository, and the real agent entrypoint — with a shell script standing in for
 * the model, so the chain runs with no network model and no spend. What the remote holds is read
 * from the remote itself, never from what the run reported.
 */
@QuarkusTest
class M0WalkingSkeletonTest {

    private static final String WORKSPACE = "TEST-acme";

    private static TestOrigin origin;

    @Inject
    RunLauncher launcher;

    @BeforeAll
    static void imagesAndOrigin() throws Exception {
        TestImages.buildAll();
        origin = TestOrigin.start();
    }

    @AfterAll
    static void stopOrigin() {
        if (origin != null) {
            origin.close();
        }
    }

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    /** The harness registry answers this script for every name; the command's harness is a label. */
    private RunCommand.ExecuteRun run(String subject, String script) throws Exception {
        QuarkusMock.installMockForType(new HarnessRegistry() {
            @Override
            public HarnessAdapter forName(String harness) {
                return new ScriptHarness(script);
            }
        }, HarnessRegistry.class);
        String runId = "run::github:" + WORKSPACE + "/app:" + subject + ":1";
        // Packed the way the orchestrator packs it: the machine account's login rides with its
        // token inside the Tink envelope, bound to this run — nothing in the worker names either.
        String packed = encryption.encryptString(
                mapper.writeValueAsString(new MachineAccountCredential(TestOrigin.USER, TestOrigin.SECRET)),
                RunCommand.scmCredentialAad(runId));
        return new RunCommand.ExecuteRun(runId,
                new RepoRef(WORKSPACE, "app"), origin.remoteUri(), "main", origin.baseCommit(),
                "spire/" + subject, "the prompt, on stdin", "script", "script", TestImages.AGENT,
                List.of(), 300, packed, null);
    }

    private static String commitAll(String prepare) {
        return prepare + " && git add -A && git commit -q -m agent";
    }

    @Test
    void anOrdinaryChangeReachesTheRemoteAuthoredByTheMachineAccount() throws Exception {
        RunCommand.ExecuteRun command = run("ordinary", commitAll("echo new > NEW.md"));

        RunResult result = launcher.launch(command, RunObserver.IGNORING);

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, result, result.toString());
        assertTrue(finished.pushedRef().endsWith("spire/ordinary"), "the guaranteed output is a pushed branch");
        assertTrue(finished.changedPaths().contains("NEW.md"), finished.changedPaths().toString());
        assertEquals(List.of(), finished.blockedPaths());
        assertNull(finished.tokenUsage(), "a harness that reports nothing is UNKNOWN, never zero");

        assertTrue(origin.hasBranch("spire/ordinary"), "the branch must exist on the real remote");
        assertTrue(origin.filesOf("spire/ordinary").contains("NEW.md"));
        assertEquals(TestOrigin.USER, origin.authorOf("spire/ordinary"),
                "the commit the agent wrote is authored by the machine account");
        assertEquals("", TestImages.docker("ps", "-aq", "--filter", "label=dev.codespire.runId=" + command.runId()),
                "the unit is destroyed after salvage");
    }

    @Test
    void thePromptReachesTheHarnessOnStdinAndNeverTheTree() throws Exception {
        // The whole SPIRE_PROMPT -> entrypoint -> stdin chain, proven by what lands on the remote:
        // the script copies its stdin into the tree, and nothing else about the prompt does.
        RunCommand.ExecuteRun command = run("prompt", commitAll("cat > SEEN.txt"));

        RunResult result = launcher.launch(command, RunObserver.IGNORING);

        assertInstanceOf(RunResult.RunFinished.class, result, result.toString());
        assertEquals("the prompt, on stdin", origin.contentOf("spire/prompt", "SEEN.txt"));
    }

    @Test
    void aWorkflowEditIsRefusedAndNothingReachesTheRemote() throws Exception {
        RunCommand.ExecuteRun command = run("ci",
                commitAll("mkdir -p .github/workflows && echo evil > .github/workflows/x.yml"));

        RunResult result = launcher.launch(command, RunObserver.IGNORING);

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, result, result.toString());
        assertNull(finished.pushedRef(), "a refused push must not deliver anything");
        assertEquals(List.of(".github/workflows/x.yml"), finished.blockedPaths());
        assertTrue(finished.refused());
        assertFalse(origin.hasBranch("spire/ci"), "nothing reached the remote");
    }

    @Test
    void anEditToAnExistingWorkflowIsRefusedToo() throws Exception {
        // The seed carries a workflow; changing it is the other half of "touches a CI file".
        RunCommand.ExecuteRun command = run("ci-edit", commitAll("echo tampered >> .github/workflows/ci.yml"));

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, launcher.launch(command, RunObserver.IGNORING));

        assertEquals(List.of(".github/workflows/ci.yml"), finished.blockedPaths());
        assertFalse(origin.hasBranch("spire/ci-edit"));
    }

    @Test
    void aHarnessThatCommitsNothingAndFailsIsReportedAsAFailureNotAnEmptySuccess() throws Exception {
        RunCommand.ExecuteRun command = run("nothing", "echo no commits; exit 3");

        RunResult result = launcher.launch(command, RunObserver.IGNORING);

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, result, result.toString());
        assertEquals("PROVIDER_ERROR", failed.cause());
        assertFalse(origin.hasBranch("spire/nothing"));
    }

}
