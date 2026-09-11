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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
        return run(subject, script, origin.baseCommit());
    }

    private RunCommand.ExecuteRun run(String subject, String script, String baseCommit) throws Exception {
        installHarness(script, Map.of());
        return command(subject, baseCommit);
    }

    /**
     * The autosave interval is an ENTRYPOINT variable, and the worker never sets one — so a test
     * reaches it the only way anything does, through the harness adapter's environment.
     */
    private RunCommand.ExecuteRun runCheckpointingEverySecond(String subject, String script) throws Exception {
        installHarness(script, Map.of("SPIRE_AUTOSAVE_SECONDS", "1"));
        return command(subject, origin.baseCommit());
    }

    private void installHarness(String script, Map<String, String> agentEnvironment) {
        QuarkusMock.installMockForType(new HarnessRegistry() {
            @Override
            public HarnessAdapter forName(String harness) {
                return new ScriptHarness(script, agentEnvironment);
            }
        }, HarnessRegistry.class);
    }

    private RunCommand.ExecuteRun command(String subject, String baseCommit) throws Exception {
        String runId = "run::github:" + WORKSPACE + "/app:" + subject + ":1";
        // Packed the way the orchestrator packs it: the machine account's login rides with its
        // token inside the Tink envelope, bound to this run — nothing in the worker names either.
        String packed = encryption.encryptString(
                mapper.writeValueAsString(new MachineAccountCredential(TestOrigin.USER, TestOrigin.SECRET)),
                RunCommand.scmCredentialAad(runId));
        return new RunCommand.ExecuteRun(runId,
                new RepoRef(WORKSPACE, "app"), origin.remoteUri(), "main", baseCommit,
                "spire/" + subject, "the prompt, on stdin", "script", "script", TestImages.AGENT,
                List.of(), 300, packed, null);
    }

    private static String commitAll(String prepare) {
        return prepare + " && git add -A && git commit -q -m agent";
    }

    /**
     * The harness says what it did, and that reaches the branch as the commit subject.
     *
     * <p>The script deliberately does NOT commit: this is the path that actually runs today. No
     * prompt had ever told an agent to commit, so the autosave was the only thing committing and
     * every branch the factory has ever pushed reads {@code autosave: work in progress} — four
     * commits on one branch and one on another, all identical and all describing nothing.
     */
    @Test
    void theHarnessesOwnSummaryBecomesTheCommitSubject() throws Exception {
        RunCommand.ExecuteRun command = run("summary",
                "echo new > NEW.md && printf 'Return an empty list instead of null' > \"$SPIRE_SUMMARY\"");

        RunResult result = launcher.launch(command, RunObserver.IGNORING);

        assertInstanceOf(RunResult.RunFinished.class, result, result.toString());
        assertEquals("Return an empty list instead of null", origin.messageOf("spire/summary"));
    }

    /** No summary is the ordinary case for a harness that knows nothing about this file. */
    @Test
    void aHarnessThatWritesNoSummaryStillCommits() throws Exception {
        RunCommand.ExecuteRun command = run("nosummary", "echo new > NEW.md");

        RunResult result = launcher.launch(command, RunObserver.IGNORING);

        assertInstanceOf(RunResult.RunFinished.class, result, result.toString());
        assertEquals("autosave: work in progress", origin.messageOf("spire/nosummary"));
    }

    /**
     * The summary is MODEL OUTPUT, so it is bounded before it becomes a commit message.
     *
     * <p>One line, control characters gone, cut to 72. A second line would silently become a commit
     * BODY, and a carriage return renders as a stray glyph on every forge that shows the subject.
     */
    @Test
    void aSummaryIsBoundedToOneShortLine() throws Exception {
        // A first line longer than the 72-char subject cap, a carriage return, and a second line —
        // all three bounds in one run, because they are one expression in the entrypoint.
        String longFirstLine = "y".repeat(80);
        RunCommand.ExecuteRun command = run("bounded", "echo new > NEW.md && printf '"
                + longFirstLine + "\\r\\nsecond line\\n' > \"$SPIRE_SUMMARY\"");

        RunResult result = launcher.launch(command, RunObserver.IGNORING);

        assertInstanceOf(RunResult.RunFinished.class, result, result.toString());
        assertEquals("y".repeat(72), origin.messageOf("spire/bounded"),
                "the first line only, carriage return removed, cut to the subject cap");
    }

    /**
     * When the init container fails, the row says WHY — not just that it exited 1.
     *
     * <p>{@code CloneMain} has always written one JSON line naming the cause, already scrubbed of
     * the git credential. Nothing read it. Every init failure reached the operator as
     * {@code init container failed with exit 1} and the reason stayed in a container log on
     * whichever host ran it — so diagnosing one meant {@code docker logs}, on a product whose run
     * screen exists to answer exactly that question.
     *
     * <p>An unreachable base commit is the deterministic way in: {@code WorkspaceClone} refuses it
     * by name, before any network work, so this asserts the channel rather than a transport mood.
     */
    @Test
    void anInitFailureCarriesTheContainersOwnCause() throws Exception {
        RunCommand.ExecuteRun command = run("badbase", commitAll("echo new > NEW.md"),
                "0123456789abcdef0123456789abcdef01234567");
        TestImages.clearUnit(command.runId());

        RunResult result = launcher.launch(command, RunObserver.IGNORING);

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, result, result.toString());
        assertTrue(failed.detail().contains("exit 1"),
                "the exit code is the fact and must survive: " + failed.detail());
        assertTrue(failed.detail().contains("CLONE_FAILED"),
                "the container's own cause must reach the row: " + failed.detail());
        assertTrue(failed.detail().contains("not reachable"),
                "including its detail, which is what names the actual problem: " + failed.detail());
        assertFalse(failed.detail().contains(TestOrigin.SECRET),
                "a failure detail is rendered on a screen; the git credential must never be in it");
    }

    /**
     * Shutdown beats a checkpoint that came due while the harness was exiting.
     *
     * <p>The autosave loop checked its stop flag only BEFORE sleeping. The harness exits during that
     * sleep — always, since the loop's first act is to sleep — so a checkpoint falling due at the
     * next tick committed the final dirty files under the GENERIC message, leaving the summary
     * checkpoint a clean tree and nothing to say. Invisible while both wrote the same constant.
     *
     * <p>Driven at an interval of one second so the tick lands inside shutdown deterministically;
     * the default 300 has the same race at its own boundary, just rarely.
     */
    @Test
    void aCheckpointFallingDueDuringShutdownDoesNotStealTheSummary() throws Exception {
        RunCommand.ExecuteRun command = runCheckpointingEverySecond("racing",
                "echo new > NEW.md && printf 'Return an empty list instead of null' > \"$SPIRE_SUMMARY\"");

        RunResult result = launcher.launch(command, RunObserver.IGNORING);

        assertInstanceOf(RunResult.RunFinished.class, result, result.toString());
        assertEquals("Return an empty list instead of null", origin.messageOf("spire/racing"),
                "the generic autosave must not win the race against the summary");
    }

    /**
     * A non-English summary is shortened without being broken.
     *
     * <p>The cap is measured in BYTES — {@code cut -c} counts bytes on BusyBox, which is the floor
     * this entrypoint targets — so cutting at it can land inside a multi-byte character and leave a
     * lone continuation byte that git renders as a stray glyph.
     *
     * <p>The summary reaches the container as octal escapes rather than as literal characters, so
     * nothing between this file and the shell can re-encode it and make the test lie about what it
     * measured — and the escapes are DERIVED from the character rather than written out, because
     * the first version of this test hand-wrote both and they encoded different characters.
     */
    @Test
    void aMultibyteSummaryIsShortenedOnACharacterBoundary() throws Exception {
        // 5 ASCII bytes + 30 three-byte characters = 95 bytes. The 72-byte cap falls inside the
        // 23rd, so the honest answer is 71 bytes: the prefix and 22 whole characters.
        String character = "\u4FEE";
        RunCommand.ExecuteRun command = run("multibyte", "echo new > NEW.md && printf 'Fix: "
                + printfOctal(character.repeat(30)) + "' > \"$SPIRE_SUMMARY\"");

        RunResult result = launcher.launch(command, RunObserver.IGNORING);

        assertInstanceOf(RunResult.RunFinished.class, result, result.toString());
        String subject = origin.messageOf("spire/multibyte");
        assertEquals("Fix: " + character.repeat(22), subject);
        assertEquals(71, subject.getBytes(StandardCharsets.UTF_8).length,
                "shortened to the byte cap without splitting the character it landed in");
    }

    /** A string's UTF-8 bytes as {@code printf} octal escapes, so only ASCII crosses to the shell. */
    private static String printfOctal(String text) {
        StringBuilder escaped = new StringBuilder();
        for (byte utf8 : text.getBytes(StandardCharsets.UTF_8)) {
            escaped.append('\\').append(Integer.toOctalString(utf8 & 0xFF));
        }
        return escaped.toString();
    }

    @Test
    void anOrdinaryChangeReachesTheRemoteAuthoredByTheMachineAccount() throws Exception {
        RunCommand.ExecuteRun command = run("ordinary", commitAll("echo new > NEW.md"));

        RunResult result = launcher.launch(command, RunObserver.IGNORING);

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, result, result.toString());
        assertTrue(finished.pushedRef().endsWith("spire/ordinary"), "the guaranteed output is a pushed branch");
        assertTrue(finished.changedPaths().contains("NEW.md"), finished.changedPaths().toString());
        assertEquals(List.of(), finished.blocked().stream().map(RunResult.BlockedChange::path).toList());
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
        String delivered = origin.contentOf("spire/prompt", "SEEN.txt");
        assertTrue(delivered.startsWith("the prompt, on stdin"),
                "the dispatched prompt arrives first and unaltered: " + delivered);
        assertTrue(delivered.contains("SPIRE_SUMMARY"),
                "and the commit instruction rides with it, for a BUILD run as much as a fix");
    }

    @Test
    void aWorkflowEditIsRefusedAndNothingReachesTheRemote() throws Exception {
        RunCommand.ExecuteRun command = run("ci",
                commitAll("mkdir -p .github/workflows && echo evil > .github/workflows/x.yml"));

        RunResult result = launcher.launch(command, RunObserver.IGNORING);

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, result, result.toString());
        assertNull(finished.pushedRef(), "a refused push must not deliver anything");
        // The KIND, not only the path. This is the only test in the repository where the real
        // publisher image, the real push gate and a real refusal meet, so it is the only place the
        // kind is asserted across the WHOLE seam rather than from hand-written JSON. Mapping it away
        // would leave the branch's headline claim resting on two tests that each hold one end of a
        // wire and neither the wire. This file is created, so ADDED.
        assertEquals(List.of(new RunResult.BlockedChange(".github/workflows/x.yml", "ADDED")),
                finished.blocked());
        assertTrue(finished.refused());
        assertFalse(origin.hasBranch("spire/ci"), "nothing reached the remote");
    }

    @Test
    void anEditToAnExistingWorkflowIsRefusedToo() throws Exception {
        // The seed carries a workflow; changing it is the other half of "touches a CI file".
        RunCommand.ExecuteRun command = run("ci-edit", commitAll("echo tampered >> .github/workflows/ci.yml"));

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, launcher.launch(command, RunObserver.IGNORING));

        // MODIFIED here where the case above is ADDED, which is the distinction the whole change
        // exists for: an operator reading a refusal needs to know whether the factory edited a
        // workflow or created one, and after M2 dispatches agents at real findings that difference
        // is the difference between a bad fix and an attempt to weaken what reviews the fix.
        assertEquals(List.of(new RunResult.BlockedChange(".github/workflows/ci.yml", "MODIFIED")),
                finished.blocked());
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
