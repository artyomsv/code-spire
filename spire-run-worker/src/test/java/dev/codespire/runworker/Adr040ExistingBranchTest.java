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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2's exit criterion, the half a container can prove: ADR-040's {@code existing} branch mode.
 *
 * <p>The criterion reads: a finding is dispatched as a fix run <b>without a tracker</b>, the run
 * <b>pushes to that pull request's own source branch</b>, and the next round reconciles the finding
 * — with <b>{@code main} and the destination branch proven still refused in that same mode</b>.
 *
 * <p><b>What this file proves and what it does not.</b> The push half is proven here, against a real
 * remote, with real containers and the real publisher image — the same machinery
 * {@code M0WalkingSkeletonTest} uses, with a shell script standing in for the model so there is no
 * network model and no spend. The reconciliation half needs a forge and lives in {@code spire-e2e};
 * the dispatch half is unit-tested in {@code FixRunDispatcherTest}. Splitting them this way is not a
 * compromise — the security floor is what a container can measure, and it is the half where being
 * wrong is expensive.
 *
 * <p><b>The refusals are the point.</b> ADR-040 lifts two rules at once for this mode — the
 * {@code spire/} namespace and {@code branch != base} — and the whole question is whether the floor
 * that survives is real. A test that only proved the permitted push would celebrate exactly the
 * lifting and say nothing about what still holds, which is the shape ADR-037 exists to close.
 */
@QuarkusTest
class Adr040ExistingBranchTest {

    private static final String WORKSPACE = "TEST-acme";

    /** The human's branch, created on the remote BEFORE the run — a pull request's source branch. */
    private static final String THEIR_BRANCH = "feature/their-work";

    private static TestOrigin origin;

    @Inject
    RunLauncher launcher;

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    @BeforeAll
    static void imagesAndOrigin() throws Exception {
        TestImages.buildAll();
        origin = TestOrigin.start();
        origin.branchFrom(THEIR_BRANCH, "main");
    }

    @AfterAll
    static void stop() {
        if (origin != null) {
            origin.close();
        }
    }

    /**
     * A fix run pushing onto a branch that already exists, which M0's publisher refused outright.
     *
     * @param branch what the run pushes to
     * @param base what it clones, and — in existing mode — the same branch
     * @param protectedBranch the pull request's destination, which the publisher refuses in every mode
     */
    private RunCommand.ExecuteRun fixRun(String subject, String script, String branch, String base,
                                         String protectedBranch) throws Exception {
        QuarkusMock.installMockForType(new HarnessRegistry() {
            @Override
            public HarnessAdapter forName(String harness) {
                return new ScriptHarness(script);
            }
        }, HarnessRegistry.class);
        String runId = "run::github:" + WORKSPACE + "/app:" + subject + ":1";
        String packed = encryption.encryptString(
                mapper.writeValueAsString(new MachineAccountCredential(TestOrigin.USER, TestOrigin.SECRET)),
                RunCommand.scmCredentialAad(runId));
        return new RunCommand.ExecuteRun(runId, new RepoRef(WORKSPACE, "app"), origin.remoteUri(),
                base, origin.commitOf(base), branch, "fix the finding", "script", "script",
                TestImages.AGENT, List.of(), 300, packed, null)
                .onExistingBranch(protectedBranch);
    }

    private static String commitAll(String prepare) {
        return prepare + " && git add -A && git commit -q -m agent";
    }

    /**
     * <b>The permitted shape: a fix lands on the human's branch, which M0 refused.</b>
     *
     * <p>Read back from the REMOTE rather than from what the run reported — the publisher could
     * report a push it did not make, and that is precisely the claim under test.
     */
    @Test
    void aFixRunPushesOntoThePullRequestsOwnSourceBranch() throws Exception {
        String marker = "FIXED-" + UUID.randomUUID().toString().substring(0, 8);
        RunCommand.ExecuteRun command = fixRun("onto-theirs",
                commitAll("echo " + marker + " > FIX.md"), THEIR_BRANCH, THEIR_BRANCH, "main");

        RunResult result = launcher.launch(command, RunObserver.IGNORING);

        RunResult.RunFinished finished =
                assertInstanceOf(RunResult.RunFinished.class, result, result.toString());
        assertTrue(finished.pushedRef().endsWith(THEIR_BRANCH),
                "the fix must land on the branch the review already watches: " + finished.pushedRef());

        assertTrue(origin.filesOf(THEIR_BRANCH).contains("FIX.md"),
                "and the REMOTE must hold it — a reported push is not a push");
        assertTrue(origin.contentOf(THEIR_BRANCH, "FIX.md").contains(marker));
        assertEquals(TestOrigin.USER, origin.authorOf(THEIR_BRANCH),
                "authored by the machine account, so the work is attributable");
        assertFalse(origin.hasBranch("spire/onto-theirs"),
                "and NO spire/ branch was created: a second branch is the outcome ADR-040 exists to "
                        + "avoid, because reconciliation is keyed per review");
    }

    /**
     * <b>A run naming the trunk never moves it — and NOT for the reason this test first claimed.</b>
     *
     * <p>What is established: drive the shape that would be catastrophic — a run naming {@code main}
     * as its branch, in the mode that lifts both namespace rules — and the trunk is untouched
     * afterwards, read back from the remote rather than trusted from what the run reported. That is
     * worth having and it is what the assertion says.
     *
     * <p><b>What is NOT established, discovered by mutation:</b> removing
     * {@code PublisherConfig.looksLikeATrunk} entirely leaves this test green. A control probe
     * (refusing every branch) reddened the two permitted-push cases, so mutations do reach the
     * container — the survival is real. Measuring it showed why: the run dies as
     * {@code RUNTIME_UNAVAILABLE, init container failed with exit 1}, before the publisher is ever
     * consulted. {@code WorkspaceClone.populate} does
     * {@code checkout().setCreateBranch(true).setName(branch)}, and a clone always materialises the
     * remote's default branch locally, so creating a second local {@code main} fails outright.
     *
     * <p>So the trunk has TWO independent guards and the outer one fires first. That is defence in
     * depth working — but it means this file cannot exercise the publisher's floor for the trunk,
     * and a test claiming otherwise would be the "assert the observable half rather than the half
     * the claim rests on" defect this project keeps paying for. The floor is tested where it is
     * reachable: {@code PublisherConfigTest}, in the module that owns it.
     *
     * <p>Recorded in {@code docs/UNVERIFIED.md} rather than left as a comment, because "the trunk
     * cannot be pushed end to end" is exactly the kind of claim someone will later want to lean on.
     */
    @Test
    void aRunNamingTheTrunkNeverMovesIt() throws Exception {
        String before = origin.commitOf("main");

        RunResult result = launcher.launch(fixRun("onto-main",
                commitAll("echo pwned > PWNED.md"), "main", "main", "develop"), RunObserver.IGNORING);

        assertPushedNothing(result, "main");
        assertEquals(before, origin.commitOf("main"),
                "the trunk moved, which is the failure this whole mode is fenced against");
        assertFalse(origin.filesOf("main").contains("PWNED.md"));
        // Named, so a future reader does not mistake this for proof of the publisher's floor.
        assertInstanceOf(RunResult.RunFailed.class, result,
                "the refusal arrives from the INIT container, not the publisher — see the javadoc");
    }

    /**
     * <b>A run may not name its own destination as its push target, and it never reaches a
     * container to find out.</b>
     *
     * <p>The first version of this test drove that shape through the launcher and asserted the
     * publisher refused it. It could not: {@code ExecuteRun}'s compact constructor refuses
     * {@code branch.equals(protectedBranch)} outright, so the command cannot be built — the test
     * failed, and it was the test that was wrong. That is defence in depth working in the right
     * order: the outer guard fires first and the container is never created.
     *
     * <p>Worth pinning HERE rather than only in the contract's own unit test, because this is the
     * assembly a real caller performs — {@code FixRunDispatcher} builds exactly this record from a
     * review row, and a destination that equalled the source would otherwise reach a paid run.
     *
     * <p>The publisher's own floor — refusing the destination whatever it is called — is the second
     * line and is tested where it lives, in {@code PublisherConfigTest}. It is unreachable from here
     * by construction, and a test that claimed to exercise it would be asserting a path nothing can
     * take.
     */
    @Test
    void aRunMayNotNameItsOwnDestinationAsItsPushTarget() {
        origin.branchFrom("develop", "main");

        assertThrowsIllegalArgument(() -> fixRun("onto-destination",
                commitAll("echo pwned > PWNED.md"), "develop", "develop", "develop"));
    }

    /**
     * And a NON-trunk destination is still refused when the run targets something else entirely.
     *
     * <p>This is the case a convention list misses: a deployment whose trunk is {@code develop} is
     * in no {@code main}/{@code master} list, which is exactly why the destination arrives as its
     * own value read from the pull request rather than being guessed. Here the run legitimately
     * pushes to the human's branch while naming {@code develop} as off-limits — the permitted shape
     * — and the assertion is that {@code develop} is untouched by it.
     */
    @Test
    void aRunNamingANonTrunkDestinationLeavesThatBranchAlone() throws Exception {
        origin.branchFrom("develop", "main");
        String before = origin.commitOf("develop");

        RunResult result = launcher.launch(fixRun("beside-destination",
                commitAll("echo ok > BESIDE.md"), THEIR_BRANCH, THEIR_BRANCH, "develop"),
                RunObserver.IGNORING);

        assertInstanceOf(RunResult.RunFinished.class, result, result.toString());
        assertEquals(before, origin.commitOf("develop"),
                "a run that names develop as off-limits must not have moved it");
        assertTrue(origin.filesOf(THEIR_BRANCH).contains("BESIDE.md"),
                "while its own push still landed");
    }

    /**
     * A run that names no destination is refused before it starts, not inside a container.
     *
     * <p>{@code ExecuteRun}'s compact constructor refuses it, and this asserts the refusal is
     * reachable from the shape a caller would actually build rather than only from a unit test.
     */
    @Test
    void existingModeWithoutADestinationNeverBecomesARun() {
        assertThrowsIllegalArgument(() -> fixRun("no-destination",
                commitAll("echo x > X.md"), THEIR_BRANCH, THEIR_BRANCH, ""));
    }

    /**
     * The push produced nothing, whatever shape the refusal took.
     *
     * <p>A refusal can arrive as a {@code RunFailed}, or as a {@code RunFinished} the push gate
     * refused — both are correct outcomes and neither must move the branch. Asserting on the OUTCOME
     * TYPE would pin an implementation detail; asserting the branch is untouched pins the property.
     */
    private static void assertPushedNothing(RunResult result, String branch) {
        if (result instanceof RunResult.RunFinished finished) {
            assertFalse(finished.pushedRef() != null && finished.pushedRef().endsWith(branch),
                    "the run reported pushing to " + branch + ": " + finished.pushedRef());
        }
    }

    private static void assertThrowsIllegalArgument(ThrowingRun body) {
        try {
            body.run();
        } catch (IllegalArgumentException expected) {
            return;
        } catch (Exception other) {
            throw new AssertionError("expected an IllegalArgumentException, got " + other, other);
        }
        throw new AssertionError("expected an IllegalArgumentException and nothing was thrown");
    }

    @FunctionalInterface
    private interface ThrowingRun {
        void run() throws Exception;
    }
}
