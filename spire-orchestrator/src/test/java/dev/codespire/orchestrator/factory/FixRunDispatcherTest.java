package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.caps.CapRefusal;
import dev.codespire.orchestrator.caps.SpendGate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether an accepted {@code /fix} becomes a dispatched run, and what that run is told.
 *
 * <p>A plain unit test. Every collaborator is faked, and every method a fake carries that this path
 * does not reach throws — an un-overridden one opens a real {@code DataSource} from a unit test, and
 * this project has hit that ten times.
 *
 * <p><b>The property this file exists for is that a refusal costs nothing.</b> No command on the
 * bus, no row in the table, no rotation slot consumed. Each of those is asserted for every refusal
 * together rather than one at a time — the observe-mode round in this same milestone found three
 * holes precisely because per-branch tests find them one at a time, and the second and third
 * survived the round that fixed the first.
 */
class FixRunDispatcherTest extends FixRunDispatcherFixture {

    // --- the happy path --------------------------------------------------------------------

    /**
     * The command carries ADR-040's existing mode, and base and branch are the same branch.
     *
     * <p>That sameness is the whole point of the mode: the fix is committed onto the branch the
     * review already watches, so the next round reconciles the original finding rather than opening
     * a second pull request nothing joins to it.
     */
    @Test
    void aDispatchedFixPushesToThePullRequestsOwnSourceBranch() {
        assertInstanceOf(FixRunDispatcher.Dispatched.class, dispatch());

        RunCommand.ExecuteRun command = launched.getFirst();
        assertEquals("feature/login", command.baseBranch());
        assertEquals("feature/login", command.branch());
        assertEquals("cafe1234", command.baseCommit());
        assertTrue(command.pushesToAnExistingBranch());
        assertEquals("develop", command.protectedBranch(),
                "the pull request's destination is the floor the publisher refuses");
    }

    /** The run id is the plan's, not a second derivation of one — two would be two encodings. */
    @Test
    void theRunIdIsThePlansAndTheResultNamesIt() {
        FixRunDispatcher.Dispatched dispatched =
                assertInstanceOf(FixRunDispatcher.Dispatched.class, dispatch());

        assertEquals("run::github:acme/web:" + THREAD + ":1", dispatched.runId());
        assertEquals(dispatched.runId(), launched.getFirst().runId());
        assertEquals(dispatched.runId(), rows.getFirst().runId());
    }

    /**
     * <b>The row is written BEFORE the command is launched.</b>
     *
     * <p>A crash between them must leave a row with no command — recoverable, and visible in the runs
     * view — rather than a command with no row. The latter is a paid run that neither cap counts,
     * which is FR-F32 failing open in exactly the direction V54 exists to prevent. It is also the
     * ordering {@code RunLaunch} assumes when it updates a row on failure.
     */
    @Test
    void theRowIsRecordedBeforeTheCommandIsLaunched() {
        dispatch();

        assertEquals(List.of("credential", "row", "launch"), order,
                "and the credential is selected last of the CHECKS, because selecting is a write");
    }

    /** The row names both cap axes and the claim, by value — transposing them is the mutation. */
    @Test
    void theRowNamesTheReviewTheFindingAndTheCommentThatAsked() {
        dispatch();

        FactoryRunProjection.QueuedRun row = rows.getFirst();
        assertEquals("FIX", row.kind());
        assertEquals(REVIEW, row.reviewId());
        assertEquals(THREAD, row.findingRef());
        assertEquals(COMMENT, row.commentId());
        assertEquals(CREDENTIAL_ID, row.harnessCredentialId());
    }

    /** A fix run uses the exact image the worker read, so every worker runs the same one (review of PR #167). */
    @Test
    void aFixRunUsesTheExactImageTheWorkerRead() {
        pinned = "TEST-registry.invalid/agent@sha256:0000000000000000000000000000000000000000000000000000000000000000";
        dispatch();

        assertEquals("TEST-registry.invalid/agent@sha256:0000000000000000000000000000000000000000000000000000000000000000", launched.getFirst().agentImage());
    }

    /**
     * The prompt is the finding, and the run is configured by the deployment.
     *
     * <p>FR-F27's premise is that the finding is a complete task specification; a command carrying a
     * location and no description would be a paid run on a line number.
     */
    @Test
    void theRunIsToldTheFindingAndTheDeploymentsHarness() {
        dispatch();

        RunCommand.ExecuteRun command = launched.getFirst();
        assertTrue(command.prompt().contains("the lock is taken in the opposite order here"),
                command.prompt());
        assertTrue(command.prompt().contains("src/Foo.java:44-48"), command.prompt());
        assertEquals("codex", command.harness());
        assertEquals("TEST-model", command.model());
        assertEquals("spire-agent-codex:1", command.agentImage());
        assertEquals(900, command.maxWallClockSeconds());
    }

    /** Both credentials are packed against THIS run id, which is their AAD — never reused. */
    @Test
    void theCredentialsArePackedAgainstThisRunsOwnId() {
        dispatch();

        String runId = launched.getFirst().runId();
        assertEquals("TEST-packed-scm:" + runId, launched.getFirst().scmCredential());
        assertEquals("TEST-packed-harness:" + runId, launched.getFirst().harnessCredential());
    }

    /** The plan is asked about the thread the finding was found on, in the repo the comment came from. */
    @Test
    void thePlanIsAskedAboutTheRightThreadAndRepository() {
        dispatch();

        assertEquals(List.of(REVIEW + "|" + THREAD + "|acme/web"), plannedFor);
        assertEquals(1, plannedFor.size(), "the plan reads two tables; asking twice reads them twice");
    }

    // --- the refusals ----------------------------------------------------------------------

    /**
     * <b>No refusal costs anything.</b>
     *
     * <p>Driven over every reachable cause at once rather than one test each. A gate that refused
     * but wrote the row anyway would leave a claim held by a run that never ran, and the finding
     * could never be fixed again.
     */
    @Test
    void noRefusalWritesARowOrLaunchesACommand() {
        record Case(String name, Runnable arrange) { }
        List<Case> causes = List.of(
                new Case("already claimed", () -> existingClaim = Optional.of("run::github:acme/web:t:1")),
                new Case("spend cap", () -> cap = SpendGate.Decision.of(
                        CapRefusal.callCapReached(10, 10))),
                new Case("unpushable", () -> plan = new FixDispatch.Refused("that pull request is merged")),
                new Case("no harness configured", () -> configuredHarness = null),
                new Case("no model configured", () -> configuredModel = null),
                new Case("unpriceable model", () -> priceable = false),
                new Case("no machine account", () -> account = Optional.empty()),
                new Case("no finding text", () -> spec = Optional.empty()),
                new Case("empty pool", () -> selection = new HarnessCredentialPool.Selection.Empty()));

        for (Case cause : causes) {
            resetToTheHappyPath();
            cause.arrange().run();

            FixRunDispatcher.Result result = dispatch();

            assertInstanceOf(FixRunDispatcher.Refused.class, result, cause.name());
            assertFalse(((FixRunDispatcher.Refused) result).why().isBlank(),
                    cause.name() + " must say why — the author gets one message");
            assertTrue(rows.isEmpty(), cause.name() + " wrote a row: " + rows);
            assertTrue(launched.isEmpty(), cause.name() + " launched a command: " + launched);
        }
    }

    /**
     * A blank comment id is refused, because without it a redelivery cannot be recognised.
     *
     * <p>Fails closed. The run id is derived from the thread plus an attempt COUNTED from existing
     * rows, so a second delivery derives a different id and passes the {@code ON CONFLICT} guard
     * that catches every other duplicate.
     */
    @Test
    void aCommandWithNoCommentIdIsRefusedRatherThanDispatchedUnclaimed() {
        for (String nothing : new String[] {null, "", "   "}) {
            resetToTheHappyPath();

            FixRunDispatcher.Result result =
                    dispatcher().dispatch(REVIEW, REPO, THREAD, nothing, finding());

            assertInstanceOf(FixRunDispatcher.Refused.class, result, "commentId=" + nothing);
            assertTrue(claimsChecked.isEmpty(), "and it never asks the claim about a blank key");
            assertTrue(rows.isEmpty(), "commentId=" + nothing);
            assertTrue(launched.isEmpty(), "commentId=" + nothing);
        }
    }

    /**
     * The duplicate check comes FIRST, ahead of every other gate.
     *
     * <p>Every gate below it is a reason to refuse a NEW request, and a redelivery is not one. A
     * repeat delivery told about a spend cap would read as a lost request rather than a finished
     * one, and the author would say {@code /fix} again.
     */
    @Test
    void aRedeliveredCommentHearsAboutItsOwnRunRatherThanTheSpendCap() {
        existingClaim = Optional.of("run::github:acme/web:thread-aaa:1");
        cap = SpendGate.Decision.of(CapRefusal.callCapReached(10, 10));
        plan = new FixDispatch.Refused("that pull request is merged");

        FixRunDispatcher.Refused refused =
                assertInstanceOf(FixRunDispatcher.Refused.class, dispatch());

        assertTrue(refused.why().contains("already started fix run"), refused.why());
        assertTrue(refused.why().contains("run::github:acme/web:thread-aaa:1"),
                "and it names the run, so the author can look at it: " + refused.why());
    }

    /** The cap's own words reach the author; re-wording them here would make two sources of truth. */
    @Test
    void theSpendCapsOwnReasonIsPassedThrough() {
        cap = SpendGate.Decision.of(CapRefusal.callCapReached(42, 42));

        FixRunDispatcher.Refused refused =
                assertInstanceOf(FixRunDispatcher.Refused.class, dispatch());
        assertTrue(refused.why().contains("42"), refused.why());
    }

    /** And so do the plan's, which is where every ADR-040 refusal is worded. */
    @Test
    void theplansOwnReasonIsPassedThrough() {
        plan = new FixDispatch.Refused("that pull request comes from a fork");

        FixRunDispatcher.Refused refused =
                assertInstanceOf(FixRunDispatcher.Refused.class, dispatch());
        assertEquals("that pull request comes from a fork", refused.why());
    }

    /**
     * An unconfigured deployment is told WHICH key to set.
     *
     * <p>A generic "not configured" makes an operator read source to find out which of the two is
     * missing, and this message is what a timeline shows them.
     */
    @Test
    void anUnconfiguredDeploymentIsToldWhichSettingIsMissing() {
        configuredHarness = null;
        assertTrue(((FixRunDispatcher.Refused) dispatch()).why().contains("SPIRE_FACTORY_FIX_HARNESS"));

        resetToTheHappyPath();
        configuredModel = null;
        assertTrue(((FixRunDispatcher.Refused) dispatch()).why().contains("SPIRE_FACTORY_FIX_MODEL"));

        resetToTheHappyPath();
        configuredHarness = "a-harness-with-no-image";
        assertTrue(((FixRunDispatcher.Refused) dispatch()).why().contains("no agent image"));
    }

    /**
     * <b>A row the projection refuses is a refusal, not a launch.</b>
     *
     * <p>{@code queued} answers false when {@code ON CONFLICT} matched a row its WHERE declined to
     * touch. Discarding that answer and dispatching anyway is a defect this projection has already
     * had once, and here it would put a second agent on a branch a run is already working on.
     */
    @Test
    void aRowTheProjectionRefusesStopsTheLaunch() {
        rowAccepted = false;

        assertInstanceOf(FixRunDispatcher.Refused.class, dispatch());
        assertTrue(launched.isEmpty(), "nothing may go on the bus once the row was refused");
    }

    /**
     * An unacknowledged launch is reported as unknown, and does NOT invite an immediate retry.
     *
     * <p>The record may already be on the topic, so "ask again" is the expensive instruction here —
     * it is how a second agent ends up on the same branch with the model paid twice. The
     * definite-miss branch says the opposite, because there nothing was published.
     */
    @Test
    void anUnacknowledgedLaunchTellsTheAuthorNotToSimplyAskAgain() {
        launchOutcome = new RunLaunch.Uncertain(new IllegalStateException("no ack"));

        FixRunDispatcher.Refused refused =
                assertInstanceOf(FixRunDispatcher.Refused.class, dispatch());
        assertTrue(refused.why().contains("could start a second one"), refused.why());

        resetToTheHappyPath();
        launchOutcome = new RunLaunch.DefiniteMiss(new IllegalStateException("refused"));
        assertTrue(((FixRunDispatcher.Refused) dispatch()).why().contains("ask again"),
                "a record that never left IS safe to ask for again");
    }
}
