package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a {@code /fix} becomes a run, and what that run is told.
 *
 * <p>The decision lives in its own class rather than inside the saga, which is the shape
 * {@code ConversationFindings} already set for {@code /finding}: the saga dispatches on a result, and
 * the rules are unit-testable without a saga fixture. Every refusal carries a reason, because the
 * author typed a command and a silent "nothing happened" is the symptom this project has paid for
 * twice.
 */
class FixDispatchTest {

    private static final String REVIEW = "review::acme/web#412";
    private static final String THREAD = "thread-aaa";
    private static final RepoRef REPO = new RepoRef("acme", "web");

    private FixTargets.PushTarget target =
            new FixTargets.PushTarget("github", "acme", "web", 412L, "feature/login", "develop",
                    "cafe1234", "OPEN", false);
    private FixRuns.Decision cap = FixRuns.Decision.ALLOWED;
    private int attempt = 1;

    /**
     * What the dispatch ASKED FOR, not just what it did with the answer.
     *
     * <p>Both fakes used to discard every parameter and return a field, which left a whole family
     * of argument-identity mutations green: transposing {@code reviewId} and {@code threadRef} in
     * the cap call makes both caps count on keys that match nothing and fail open FOREVER; doing it
     * in the attempt call makes every fix attempt 1, so the second collides on a run id and is
     * dropped by the worker's claim as a redelivery. Silently, both of them.
     */
    private String askedCapReview;
    private String askedCapFinding;
    private int askedPerFinding;
    private int askedPerReview;
    private String askedAttemptReview;
    private String askedAttemptFinding;

    private FixDispatch dispatch() {
        FixDispatch dispatch = new FixDispatch();
        dispatch.targets = new FixTargets() {
            @Override
            public Optional<PushTarget> forReview(String reviewId) {
                return Optional.ofNullable(target);
            }
        };
        dispatch.fixRuns = new FixRuns() {
            @Override
            public Decision decide(String reviewId, String findingRef, int perFinding, int perReview) {
                askedCapReview = reviewId;
                askedCapFinding = findingRef;
                askedPerFinding = perFinding;
                askedPerReview = perReview;
                return cap;
            }

            @Override
            public int nextAttempt(String reviewId, String findingRef) {
                askedAttemptReview = reviewId;
                askedAttemptFinding = findingRef;
                return attempt;
            }

            // The recorded trap: an un-overridden method on a fake opens a real DataSource from a
            // plain unit test. These two are not on this path, and saying so loudly is cheaper
            // than discovering it as an NPE.
            @Override
            public int forFinding(String reviewId, String findingRef) {
                throw new AssertionError("the dispatch must go through decide(), not count directly");
            }

            @Override
            public int forReview(String reviewId) {
                throw new AssertionError("the dispatch must go through decide(), not count directly");
            }
        };
        return dispatch;
    }


    /**
     * <b>The two copies of this rule must agree, and nothing else makes them.</b>
     *
     * <p>{@code FixTargets.PushTarget.isPushable()} answers one boolean; {@code whyNotPushable}
     * answers WHICH cause, because an author needs to know which. That is two encodings of one rule,
     * which is exactly the shape this project has paid for before — two credential scrubbers whose
     * rules quietly diverged, and the weaker one ran in the container holding the write token.
     *
     * <p>So this drives every combination and asserts the two never disagree. A cause added to one
     * and not the other fails here rather than in a deployment, where the symptom would be a fix run
     * dispatched at a target the read model considers unpushable.
     */
    @Test
    void theTwoEncodingsOfPushableNeverDisagree() {
        for (String state : new String[] {"OPEN", "MERGED", "CLOSED"}) {
            // null is the pre-V55 row, and it is an AXIS rather than a case because it is exactly
            // the value rs.getBoolean would silently deliver as false.
            for (Boolean fork : new Boolean[] {false, true, null}) {
                for (String branch : new String[] {"feature/login", "", "   "}) {
                    // Whitespace on BOTH axes. It was seeded for the branch and not the commit, so
                    // isBlank -> isEmpty on the commit survived here AND in FixTargetsTest — the
                    // same finding as an earlier round, on the axis that round did not cover.
                    for (String commit : new String[] {"cafe1234", "", "   "}) {
                        // And the destination, which had no axis at all: a fifth cause keyed on it
                        // survived all 8 original cases, because every one of them named "develop".
                        // A matrix is blind to any cause outside the axes it varies — worth
                        // remembering about matrices generally, not about this one.
                        for (String dest : new String[] {"develop", "", "   "}) {
                            var candidate = new FixTargets.PushTarget("github", "acme", "web", 412L,
                                    branch, dest, commit, state, fork);
                            target = candidate;
                            boolean planned = dispatch().plan(REVIEW, THREAD, REPO)
                                    instanceof FixDispatch.Planned;
                            assertEquals(candidate.isPushable(), planned,
                                    "disagreement on " + state + "/fork=" + fork + "/branch='" + branch
                                            + "'/commit='" + commit + "'/dest='" + dest + "'");
                        }
                    }
                }
            }
        }
    }

    @Test
    void plansARunThatPushesToThePullRequestsOwnSourceBranch() {
        FixDispatch.Planned planned = assertInstanceOf(FixDispatch.Planned.class,
                dispatch().plan(REVIEW, THREAD, REPO));

        // base and branch are the SAME branch, which is the whole point of ADR-040's existing mode:
        // the fix is committed onto the branch the review already watches.
        assertEquals("feature/login", planned.baseBranch());
        assertEquals("feature/login", planned.branch());
        assertEquals("develop", planned.protectedBranch());
        assertEquals("cafe1234", planned.baseCommit());
        // Transposing these two was a survivor, and the run id is built from them.
        assertEquals(ScmType.GITHUB, planned.scmType());
        assertEquals("acme", planned.workspace());
        assertEquals("web", planned.slug());
    }

    /**
     * <b>Which axis each collaborator was asked about, and with which numbers.</b>
     *
     * <p>Nothing asserted this, so transposing the two keys in either call passed 8/8 — and the cap
     * transposition makes both caps count on keys that match nothing, which is FR-F32 failing open
     * with no symptom at all. The cap NUMBERS were pinned by nothing either, so widening
     * {@code MAX_PER_FINDING} to 200 was equally invisible.
     */
    @Test
    void asksEachCollaboratorAboutTheRightAxis() {
        dispatch().plan(REVIEW, THREAD, REPO);

        assertEquals(REVIEW, askedCapReview);
        assertEquals(THREAD, askedCapFinding);
        assertEquals(FixDispatch.MAX_PER_FINDING, askedPerFinding);
        assertEquals(FixDispatch.MAX_PER_REVIEW, askedPerReview);
        assertEquals(REVIEW, askedAttemptReview);
        assertEquals(THREAD, askedAttemptFinding);
    }

    /**
     * <b>The cap NUMBERS, not merely that they are passed through.</b>
     *
     * <p>The axis test above asserts the dispatch hands each collaborator the right constant, and it
     * does that by comparing against the constants themselves — so widening either to 999 changes
     * both sides of its assertion and it stays green. FR-F32 bounds a runaway loop; a bound nothing
     * pins is a bound that can be raised by a typo, silently, on the guard that stops a fix-review-fix
     * chain spending without end.
     *
     * <p>They must also differ, or transposing them in the call would prove nothing.
     */
    @Test
    void theCapsAreTheNumbersFrF32Asks() {
        assertEquals(2, FixDispatch.MAX_PER_FINDING);
        assertEquals(5, FixDispatch.MAX_PER_REVIEW);
        assertTrue(FixDispatch.MAX_PER_FINDING != FixDispatch.MAX_PER_REVIEW,
                "equal caps would make the axis assertions above vacuous");
    }

    /**
     * The run id embeds the finding and the attempt, so a second fix for one finding is a different
     * run rather than a redelivery the worker's claim silently drops.
     */
    @Test
    void derivesARunIdFromTheFindingAndTheAttempt() {
        attempt = 3;

        FixDispatch.Planned planned = assertInstanceOf(FixDispatch.Planned.class,
                dispatch().plan(REVIEW, THREAD, REPO));

        // Exact, because the run id IS the address: FactoryRunProjection parses it straight back
        // into provider_type/workspace/slug. A substring check accepts a transposed id that files
        // every row and every charge line under a repository that does not exist.
        assertEquals("run::github:acme/web:" + THREAD + ":3", planned.runId());
    }

    /**
     * ADR-040 §3's repository match, which existed as a tested method nothing called.
     *
     * <p>The hazard is one step less exotic than the fork gap: a branch name resolved against one
     * repository and pushed against another. The shape was inside-out — {@code plan} resolved the
     * coordinates from the review and REPORTED them, rather than being told the ones the comment
     * arrived on and PROVING they match.
     */
    @Test
    void refusesWhenTheReviewBelongsToADifferentRepository() {
        FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                dispatch().plan(REVIEW, THREAD, new RepoRef("acme", "other-repo")));

        assertTrue(refused.why().contains("different repository"), refused.why());
    }

    /**
     * The cap outranks a MISSING pull request too, not only a merged one.
     *
     * <p>The sibling case covers cap-versus-state; moving the cap check below the "no such review"
     * branch survived it, because that case runs with the cap allowing. Same argument: a capped
     * finding should hear about the cap rather than be sent to investigate a review row.
     */
    @Test
    void reportsTheCapRatherThanTheMissingPullRequest() {
        cap = FixRuns.Decision.refused("this pull request has already had 3 fix run(s)");
        target = null;

        FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                dispatch().plan(REVIEW, THREAD, REPO));
        assertTrue(refused.why().contains("already had 3"), refused.why());
    }

    @Test
    void refusesWhenTheReviewIsNotKnown() {
        target = null;

        FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                dispatch().plan(REVIEW, THREAD, REPO));
        assertTrue(refused.why().contains("no pull request"), refused.why());
    }

    /** Merged, closed, forked, or missing a branch — all one answer to the author, with the reason. */
    @Test
    void refusesWhenThePullRequestCannotBePushedTo() {
        target = new FixTargets.PushTarget("github", "acme", "web", 412L, "feature/login", "develop",
                "cafe1234", "MERGED", false);

        FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                dispatch().plan(REVIEW, THREAD, REPO));
        assertTrue(refused.why().contains("no longer open"), refused.why());
    }

    @Test
    void refusesAForkWithAReasonThatNamesTheFork() {
        target = new FixTargets.PushTarget("github", "acme", "web", 412L, "feature/login", "develop",
                "cafe1234", "OPEN", true);

        FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                dispatch().plan(REVIEW, THREAD, REPO));
        assertTrue(refused.why().contains("fork"), refused.why());
    }

    /**
     * A blank destination is a REFUSAL, not an exception — and it was an exception.
     *
     * <p>{@code dest_branch} becomes {@code Planned.protectedBranch}, and {@code ExecuteRun}'s
     * compact constructor throws on a blank one in existing mode. So the moment the saga wires this
     * class up, a review row that never recorded a destination would raise an exception on a Kafka
     * consumer — a redelivery, refusing forever, with nothing said to the author who typed /fix.
     * The matrix above cannot be the only cover: it asserts agreement between two readings of one
     * rule, and a rule missing from both agrees with itself perfectly.
     */
    @Test
    void refusesABlankDestinationRatherThanThrowingLater() {
        target = new FixTargets.PushTarget("github", "acme", "web", 412L, "feature/login", "",
                "cafe1234", "OPEN", false);

        FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                dispatch().plan(REVIEW, THREAD, REPO));
        assertTrue(refused.why().contains("destination"), refused.why());
    }

    /**
     * A branch opened onto itself is a REFUSAL, for the same reason a blank destination is.
     *
     * <p>No forge produces this row, which is the argument for guarding it rather than against:
     * the row is what the deployment last SAW, and trusting it fails by THROWING rather than by
     * answering wrongly. {@code ExecuteRun} refuses a run whose branch equals its protected branch
     * — correctly — and refuses by throwing, which on a Kafka consumer is a redelivery.
     *
     * <p>Whitespace on one side, because both this class and {@code ExecuteRun} compare stripped:
     * an exact match here would pass a trailing space through to the throw it exists to prevent.
     */
    @Test
    void refusesAPullRequestRecordedAsOpenedFromABranchOntoItself() {
        for (String dest : new String[] {"feature/login", "feature/login  "}) {
            target = new FixTargets.PushTarget("github", "acme", "web", 412L, "feature/login", dest,
                    "cafe1234", "OPEN", false);

            FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                    dispatch().plan(REVIEW, THREAD, REPO), "dest='" + dest + "'");
            assertTrue(refused.why().contains("onto itself"), refused.why());
        }
    }

    /**
     * <b>And the plan a dispatch produces is one {@code ExecuteRun} accepts.</b>
     *
     * <p>The end-to-end property the three guards above exist for, asserted once rather than
     * inferred from them: every refusal is a wording decision, but the POINT of each is that what
     * survives can be built. Construction is where the throws live, so building it here is what
     * makes a fourth unguarded column fail in this class rather than in a consumer.
     */
    @Test
    void aPlannedRunIsOneTheCommandRecordWillAccept() {
        FixDispatch.Planned planned = assertInstanceOf(FixDispatch.Planned.class,
                dispatch().plan(REVIEW, THREAD, REPO));

        RunCommand.ExecuteRun command = new RunCommand.ExecuteRun(planned.runId(),
                new RepoRef(planned.workspace(), planned.slug()), "https://example.invalid/x.git",
                planned.baseBranch(), planned.baseCommit(), planned.branch(), "TEST-prompt",
                "codex", "TEST-model", "TEST-image", List.of(), 900,
                "TEST-scm-credential", "TEST-harness-credential")
                .onExistingBranch(planned.protectedBranch());

        assertTrue(command.pushesToAnExistingBranch());
        assertEquals("feature/login", command.branch());
        assertEquals("develop", command.protectedBranch());
    }

    /**
     * A row written before V55 is told to push once, NOT told its pull request is a fork.
     *
     * <p>Two distinct causes deliberately, and this asserts the wording keeps them distinct: the row
     * is very probably an ordinary branch pull request, and sending its author to argue with a claim
     * about forks would waste the one message they get.
     */
    @Test
    void refusesARowWhoseProvenanceWasNeverRecordedWithoutCallingItAFork() {
        target = new FixTargets.PushTarget("github", "acme", "web", 412L, "feature/login", "develop",
                "cafe1234", "OPEN", null);

        FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                dispatch().plan(REVIEW, THREAD, REPO));
        assertTrue(refused.why().contains("before this deployment"), refused.why());
        assertFalse(refused.why().contains("comes from a fork"), refused.why());
    }

    /**
     * The unrecognised-SCM refusal, which every other case here was blind to.
     *
     * <p>All of them name {@code "github"}, so deleting that branch entirely left the suite green and
     * moved the failure into {@code ScmType.fromProviderType(...).get()} — an exception on a Kafka
     * consumer rather than a refusal, which is the same defect as the blank destination above and
     * reaches the author the same way: not at all.
     */
    @Test
    void refusesAReviewRecordedUnderAnScmThisBuildDoesNotKnow() {
        target = new FixTargets.PushTarget("TEST-not-a-real-scm", "acme", "web", 412L,
                "feature/login", "develop", "cafe1234", "OPEN", false);

        FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                dispatch().plan(REVIEW, THREAD, REPO));
        assertTrue(refused.why().contains("does not recognise"), refused.why());
        assertTrue(refused.why().contains("TEST-not-a-real-scm"),
                "the operator needs the value to fix the registration: " + refused.why());
    }

    /** The cap's own words reach the author — re-wording them here would make two sources of truth. */
    @Test
    void refusesWhenACapSaysSoAndPassesTheCapsReasonThrough() {
        cap = FixRuns.Decision.refused("this finding has already had 2 fix run(s)");

        FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                dispatch().plan(REVIEW, THREAD, REPO));
        assertEquals("this finding has already had 2 fix run(s)", refused.why());
    }

    /**
     * <b>The cap is consulted before the target is proven pushable, and the order is deliberate.</b>
     *
     * <p>A capped finding on a merged pull request should say it is capped: the cap is the durable
     * fact an operator set, while "merged" is a state that changed. Reporting the transient reason
     * would send someone to reopen a pull request that the cap would refuse anyway.
     */
    @Test
    void reportsTheCapRatherThanTheStateWhenBothWouldRefuse() {
        cap = FixRuns.Decision.refused("this pull request has already had 3 fix run(s)");
        target = new FixTargets.PushTarget("github", "acme", "web", 412L, "feature/login", "develop",
                "cafe1234", "MERGED", false);

        FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                dispatch().plan(REVIEW, THREAD, REPO));
        assertTrue(refused.why().contains("already had 3"), refused.why());
    }
}
