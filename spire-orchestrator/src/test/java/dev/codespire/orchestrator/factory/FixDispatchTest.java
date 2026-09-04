package dev.codespire.orchestrator.factory;

import dev.codespire.orchestrator.readmodel.FindingProjection;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private FixTargets.PushTarget target =
            new FixTargets.PushTarget("github", "acme", "web", 412L, "feature/login", "develop",
                    "cafe1234", "OPEN", false);
    private FixRuns.Decision cap = FixRuns.Decision.ALLOWED;
    private int attempt = 1;

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
                return cap;
            }

            @Override
            public int nextAttempt(String reviewId, String findingRef) {
                return attempt;
            }
        };
        return dispatch;
    }

    private static FindingProjection.TargetFinding finding() {
        return new FindingProjection.TargetFinding(77L, 2, "src/Foo.java", 44, 48, "HIGH", null, "review");
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
            for (boolean fork : new boolean[] {false, true}) {
                for (String branch : new String[] {"feature/login", "", "   "}) {
                    for (String commit : new String[] {"cafe1234", ""}) {
                        var candidate = new FixTargets.PushTarget("github", "acme", "web", 412L,
                                branch, "develop", commit, state, fork);
                        target = candidate;
                        boolean planned = dispatch().plan(REVIEW, THREAD, finding())
                                instanceof FixDispatch.Planned;
                        assertEquals(candidate.isPushable(), planned,
                                "disagreement on " + state + "/fork=" + fork + "/branch='" + branch
                                        + "'/commit='" + commit + "'");
                    }
                }
            }
        }
    }

    @Test
    void plansARunThatPushesToThePullRequestsOwnSourceBranch() {
        FixDispatch.Planned planned = assertInstanceOf(FixDispatch.Planned.class,
                dispatch().plan(REVIEW, THREAD, finding()));

        // base and branch are the SAME branch, which is the whole point of ADR-040's existing mode:
        // the fix is committed onto the branch the review already watches.
        assertEquals("feature/login", planned.baseBranch());
        assertEquals("feature/login", planned.branch());
        assertEquals("develop", planned.protectedBranch());
        assertEquals("cafe1234", planned.baseCommit());
    }

    /**
     * The run id embeds the finding and the attempt, so a second fix for one finding is a different
     * run rather than a redelivery the worker's claim silently drops.
     */
    @Test
    void derivesARunIdFromTheFindingAndTheAttempt() {
        attempt = 3;

        FixDispatch.Planned planned = assertInstanceOf(FixDispatch.Planned.class,
                dispatch().plan(REVIEW, THREAD, finding()));

        assertTrue(planned.runId().contains(THREAD), planned.runId());
        assertTrue(planned.runId().endsWith(":3"), planned.runId());
    }

    @Test
    void refusesWhenTheReviewIsNotKnown() {
        target = null;

        FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                dispatch().plan(REVIEW, THREAD, finding()));
        assertTrue(refused.why().contains("no pull request"), refused.why());
    }

    /** Merged, closed, forked, or missing a branch — all one answer to the author, with the reason. */
    @Test
    void refusesWhenThePullRequestCannotBePushedTo() {
        target = new FixTargets.PushTarget("github", "acme", "web", 412L, "feature/login", "develop",
                "cafe1234", "MERGED", false);

        FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                dispatch().plan(REVIEW, THREAD, finding()));
        assertTrue(refused.why().contains("no longer open"), refused.why());
    }

    @Test
    void refusesAForkWithAReasonThatNamesTheFork() {
        target = new FixTargets.PushTarget("github", "acme", "web", 412L, "feature/login", "develop",
                "cafe1234", "OPEN", true);

        FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                dispatch().plan(REVIEW, THREAD, finding()));
        assertTrue(refused.why().contains("fork"), refused.why());
    }

    /** The cap's own words reach the author — re-wording them here would make two sources of truth. */
    @Test
    void refusesWhenACapSaysSoAndPassesTheCapsReasonThrough() {
        cap = FixRuns.Decision.refused("this finding has already had 2 fix run(s)");

        FixDispatch.Refused refused = assertInstanceOf(FixDispatch.Refused.class,
                dispatch().plan(REVIEW, THREAD, finding()));
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
                dispatch().plan(REVIEW, THREAD, finding()));
        assertTrue(refused.why().contains("already had 3"), refused.why());
    }
}
