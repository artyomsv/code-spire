package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-F32's two caps, and the attempt number a re-dispatch takes.
 *
 * <p><b>Two axes, because one does not bound the loop the requirement is about.</b> Counting per
 * FINDING stops repeated attempts at one stubborn finding. It cannot stop the runaway FR-F32 names —
 * a finding spawns a fix, whose review raises a finding, which spawns a fix — because every hop
 * raises a NEW finding with a new identity, so a per-finding counter sees one run each and never
 * reaches N while reporting itself satisfied. The per-REVIEW axis is what bounds that chain, and
 * under ADR-040 a fix pushes to the branch the review already watches, so one review IS the chain.
 */
@QuarkusTest
class FixRunsTest {

    private static final String REVIEW = "review::TEST-WS/TEST-REPO#7001";
    private static final String OTHER_REVIEW = "review::TEST-WS/TEST-REPO#7002";
    private static final String FINDING = "thread-aaa";
    private static final String OTHER_FINDING = "thread-bbb";

    @Inject
    FixRuns fixRuns;

    @Inject
    FactoryRunProjection projection;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        // By WORKSPACE, not by run-id prefix. The prefix rule missed every id built through RunIds,
        // which spells the platform first -- so rows leaked between cases and polluted the counts
        // this class exists to assert. The workspace is in every id these tests create.
        exec("DELETE FROM factory_run WHERE workspace = 'TEST-WS'");
    }

    /**
     * <b>The row the projection actually writes is the row the caps count.</b>
     *
     * <p>Every other case here hand-writes its INSERT, which proves the QUERIES and proves nothing
     * about the writer. A projection that dropped kind, review_id or finding_ref would leave every
     * one of them green while the cap silently counted nothing — the cap failing open, which is the
     * direction that matters. So this goes through the real writer.
     */
    @Test
    void aFixRunWrittenByTheProjectionIsCountedByBothCaps() {
        FactoryRunProjection.QueuedRun row = new FactoryRunProjection.QueuedRun(
                "run::github:TEST-WS/TEST-REPO:" + FINDING + ":1", "codex", "TEST-MODEL", "main",
                "TESTSHA0", "feature/login", "machine-account", null).asFixFor(REVIEW, FINDING, "TEST-comment-1");

        assertTrue(projection.queued(row), "the row must be written");
        assertEquals(1, fixRuns.forFinding(REVIEW, FINDING));
        assertEquals(1, fixRuns.forReview(REVIEW));
    }

    /**
     * <b>The claim is on the COMMENT, and this is the case that shows why it had to be.</b>
     *
     * <p>The run id is derived from the finding's thread plus {@code nextAttempt}, and
     * {@code nextAttempt} COUNTS the rows already written. So a redelivered command derives a
     * HIGHER attempt than the first delivery did, produces a different run id, and sails straight
     * through {@code ON CONFLICT (run_id)} — the one guard that catches every other duplicate. The
     * numbering defeats it. This drives exactly that sequence and asserts the comment claim sees
     * what the run id could not.
     */
    @Test
    void aRedeliveredCommandDerivesADifferentRunIdAndIsCaughtByTheCommentClaimAlone() {
        String comment = "TEST-comment-redelivered";
        int firstAttempt = fixRuns.nextAttempt(REVIEW, FINDING);
        assertTrue(projection.queued(fixRow(firstAttempt, comment)), "the first delivery is written");

        int secondAttempt = fixRuns.nextAttempt(REVIEW, FINDING);
        assertNotEquals(firstAttempt, secondAttempt,
                "if the attempt did NOT move, this test proves nothing about the claim");

        // The run id guard would have let this through: it is a genuinely new id.
        assertNotEquals(fixRow(firstAttempt, comment).runId(), fixRow(secondAttempt, comment).runId());
        // The claim is what sees it, and it names the run already bought so a refusal can say which.
        assertEquals("run::github:TEST-WS/TEST-REPO:" + FINDING + ":" + firstAttempt,
                projection.fixRunFor(REVIEW, comment).orElseThrow());
    }

    /** A comment that has bought nothing reads as empty — seeded first, or this proves nothing. */
    @Test
    void aCommentThatHasBoughtNoRunReadsAsEmpty() {
        assertTrue(projection.queued(fixRow(1, "TEST-comment-a")));

        assertTrue(projection.fixRunFor(REVIEW, "TEST-comment-b").isEmpty());
    }

    /**
     * A build run is invisible to the claim, however it was written.
     *
     * <p>The index is partial on {@code kind = 'FIX'} and the query repeats that condition. Both
     * halves matter: dropping it from the query would make a build run whose comment column was
     * somehow set refuse a fix, and dropping it from the index would make every build row compete
     * for uniqueness on a column that means nothing to it.
     */
    @Test
    void theClaimSeesOnlyFixRuns() {
        assertTrue(projection.queued(new FactoryRunProjection.QueuedRun(
                "run::github:TEST-WS/TEST-REPO:build-subject:1", "codex", "TEST-MODEL", "main",
                "TESTSHA0", "spire/build-subject", "machine-account", null, "BUILD", null, null,
                "TEST-comment-on-a-build")));

        assertTrue(projection.fixRunFor(REVIEW, "TEST-comment-on-a-build").isEmpty());
    }

    /** A fix row cannot be written without its claim; the throw is the caller's bug, named. */
    @Test
    void aFixRowWithoutTheCommentThatAskedIsRefused() {
        for (String nothing : new String[] {null, "", "   "}) {
            assertThrows(IllegalArgumentException.class,
                    () -> fixRow(1, "x").asFixFor(REVIEW, FINDING, nothing), "commentId=" + nothing);
        }
    }

    /** And asking the claim about no comment at all is a caller bug too, not an empty answer. */
    @Test
    void theClaimRefusesToBeAskedAboutNoComment() {
        for (String nothing : new String[] {null, "", "   "}) {
            assertThrows(IllegalArgumentException.class, () -> projection.fixRunFor(REVIEW, nothing),
                    "commentId=" + nothing);
            assertThrows(IllegalArgumentException.class,
                    () -> projection.fixRunFor(nothing, "TEST-comment-a"), "reviewId=" + nothing);
        }
    }

    /**
     * <b>Two reviews, one comment id, and they must not be the same claim.</b>
     *
     * <p>A comment id is the FORGE's own — every ingress passes {@code comment.id} /
     * {@code noteId} straight through — so it is unique within one forge and nowhere else. A
     * deployment holding a GitHub and a GitLab provider, or two self-hosted GitLabs whose note ids
     * both start at 1, produces this exact collision.
     *
     * <p>Keyed on the comment alone it does two things, and both are wrong: a legitimate
     * {@code /fix} is refused while naming ANOTHER workspace's run id in this review's durable
     * history, and the race the unique index backstops dead-letters after {@code pool.select()}
     * has already spent a rotation slot. The second half of this test is the discriminating one —
     * without the scope in the query, the second insert cannot even be written.
     */
    @Test
    void oneCommentIdOnTwoReviewsIsTwoClaims() {
        String shared = "TEST-comment-42";
        String other = "review::gitlab:TEST-OTHER/TEST-OTHER:9";
        assertTrue(projection.queued(fixRow(1, shared)));
        assertTrue(projection.queued(new FactoryRunProjection.QueuedRun(
                "run::gitlab:TEST-OTHER/TEST-OTHER:" + FINDING + ":1", "codex", "TEST-MODEL",
                "main", "TESTSHA0", "spire/other", "machine-account", null, "FIX", other,
                FINDING, shared)),
                "the unique index must not treat two reviews' comment 42 as one claim");

        assertEquals("run::github:TEST-WS/TEST-REPO:" + FINDING + ":1",
                projection.fixRunFor(REVIEW, shared).orElseThrow());
        assertEquals("run::gitlab:TEST-OTHER/TEST-OTHER:" + FINDING + ":1",
                projection.fixRunFor(other, shared).orElseThrow(),
                "each review must read back ITS run, not whichever row the planner yielded first");
    }

    /**
     * A re-arm may not MOVE a claim from one comment to another.
     *
     * <p>The re-arm exists for a dispatch the broker refused: the same request, sent again. Every
     * other component of the row's identity is compared before it is allowed, and an earlier round
     * found three that were not — a BUILD row re-armed as FIX stayed BUILD, so neither cap counted
     * it. The comment is the fourth, and it is the one the SPEND claim rests on: if a re-arm could
     * rewrite it, one comment would be released to buy again while the unique index — which is an
     * index, not a row comparison — saw nothing move.
     */
    @Test
    void aReArmMayNotChangeWhichCommentBoughtTheRun() {
        String runId = "run::github:TEST-WS/TEST-REPO:" + FINDING + ":1";
        assertTrue(projection.queued(fixRow(1, "TEST-comment-original")));
        projection.dispatchFailed(runId, "the broker did not acknowledge the command");

        assertFalse(projection.queued(fixRow(1, "TEST-comment-different")),
                "a differing retry must match no row here, and be refused by the caller");
        assertEquals(runId, projection.fixRunFor(REVIEW, "TEST-comment-original").orElseThrow(),
                "the original claim must still hold");
        assertTrue(projection.fixRunFor(REVIEW, "TEST-comment-different").isEmpty(),
                "and the other comment must not have acquired it");

        // The negative control: the IDENTICAL retry is the one this path exists for, and it works.
        assertTrue(projection.queued(fixRow(1, "TEST-comment-original")),
                "an identical retry re-arms, or the comparison above is simply refusing everything");
    }

    private static FactoryRunProjection.QueuedRun fixRow(int attempt, String commentId) {
        return new FactoryRunProjection.QueuedRun(
                "run::github:TEST-WS/TEST-REPO:" + FINDING + ":" + attempt, "codex", "TEST-MODEL",
                "main", "TESTSHA0", "feature/login", "machine-account", null)
                .asFixFor(REVIEW, FINDING, commentId);
    }

    /** And a build run written the same way is counted by neither. */
    @Test
    void aBuildRunWrittenByTheProjectionIsCountedByNeitherCap() {
        assertTrue(projection.queued(new FactoryRunProjection.QueuedRun(
                "run::github:TEST-WS/TEST-REPO:subject:1", "codex", "TEST-MODEL", "main",
                "TESTSHA0", "spire/subject", "machine-account", null)));

        assertEquals(0, fixRuns.forReview(REVIEW));
    }

    @Test
    void countsNothingBeforeAnyFixRunExists() {
        assertEquals(0, fixRuns.forFinding(REVIEW, FINDING));
        assertEquals(0, fixRuns.forReview(REVIEW));
    }

    @Test
    void countsTheFixRunsAlreadyDispatchedForOneFinding() {
        insertFixRun("run::TEST-1", REVIEW, FINDING);
        insertFixRun("run::TEST-2", REVIEW, FINDING);

        assertEquals(2, fixRuns.forFinding(REVIEW, FINDING));
    }

    /**
     * <b>A run the broker never accepted is not a run this finding has had.</b>
     *
     * <p>Both caps count runs that HAPPENED. A dispatch the broker refused never executed and
     * never spent — {@code FactoryRunProjection} already treats exactly that row as re-armable, so
     * the operator's identical retry restarts it. Counting it charges the author for an
     * infrastructure fault, and with {@code MAX_PER_FINDING = 2} two broker outages retire a
     * finding forever, while the refusal tells its author it "has already had 2 fix run(s)" about
     * two runs that landed nowhere. Five retire a whole review through the other axis.
     *
     * <p><b>A run that FAILED is not the same thing, and that is the discriminating half.</b> The
     * cheap simplification is {@code AND status <> 'failed'}: it passes every assertion about the
     * broker, because a run the broker never accepted really is a failed row. What it also excludes
     * is every run that started, executed, spent money and then died — a dropped commit, a failed
     * salvage, a non-zero exit. Those are exactly the runs FR-F32 exists to bound, and under that
     * filter a finding could be retried without limit as long as each attempt died. So the filter
     * names the CAUSE and not the status, and the third row here is what says so.
     *
     * <p>{@code DISPATCH_UNCERTAIN} is not excluded either: that run may be executing, so counting
     * it is the fail-closed answer. It writes a status of its own rather than {@code failed}, so it
     * does not discriminate the simplification above — it is asserted for its own sake.
     */
    @Test
    void aDispatchTheBrokerNeverAcceptedIsCountedByNeitherCap() {
        insertFixRun("run::TEST-1", REVIEW, FINDING);
        insertFixRun("run::TEST-2", REVIEW, FINDING);
        projection.dispatchFailed("run::TEST-2", "the broker did not acknowledge the command");

        assertEquals(1, fixRuns.forFinding(REVIEW, FINDING),
                "only the run that reached a partition counts");
        assertEquals(1, fixRuns.forReview(REVIEW), "and the review axis says the same");

        // Ran, spent, died. The cap is about money already gone, so this one counts.
        insertFailedFixRun("run::TEST-3", REVIEW, FINDING, "SALVAGE_FAILED");
        assertEquals(2, fixRuns.forFinding(REVIEW, FINDING),
                "a run that executed and then failed has still had its attempt");
        assertEquals(2, fixRuns.forReview(REVIEW));

        projection.dispatchUncertain("run::TEST-1", "sent and never acknowledged");
        assertEquals(2, fixRuns.forFinding(REVIEW, FINDING),
                "an unresolved dispatch may be executing, so it must still be counted");
        assertEquals(2, fixRuns.forReview(REVIEW));
    }

    /**
     * The per-review axis counts the whole chain, which is the point: each hop of the runaway is a
     * DIFFERENT finding, so a per-finding count of them all reads 1.
     */
    @Test
    void countsEveryFixRunOnAReviewAcrossDifferentFindings() {
        insertFixRun("run::TEST-1", REVIEW, FINDING);
        insertFixRun("run::TEST-2", REVIEW, OTHER_FINDING);

        assertEquals(1, fixRuns.forFinding(REVIEW, FINDING), "each finding has one run of its own");
        assertEquals(1, fixRuns.forFinding(REVIEW, OTHER_FINDING));
        assertEquals(2, fixRuns.forReview(REVIEW), "but the review has seen two — this is the chain");
    }

    /** Two reviews are two chains; one must not spend the other's budget. */
    @Test
    void doesNotCountAnotherReviewsFixRuns() {
        insertFixRun("run::TEST-1", OTHER_REVIEW, FINDING);

        assertEquals(0, fixRuns.forFinding(REVIEW, FINDING));
        assertEquals(0, fixRuns.forReview(REVIEW));
    }

    /** A BUILD run carrying no target at all is outside both counts. */
    @Test
    void doesNotCountABuildRunThatCarriesNoFindingTarget() {
        insertBuildRun("run::TEST-build");

        assertEquals(0, fixRuns.forReview(REVIEW));
    }

    /**
     * <b>A non-fix run may not carry a review at all — and getting here took two wrong answers.</b>
     *
     * <p>The first CHECK was written as a biconditional against NULL:
     * {@code (kind = 'FIX') = (review_id IS NOT NULL AND finding_ref IS NOT NULL)}. Its right side
     * is an AND, so a non-FIX row satisfied it by failing EITHER conjunct — making
     * {@code (BUILD, review_id, NULL)} a legal row that a count without the {@code kind} filter
     * would have charged to that review's fix budget. So the filter WAS load-bearing, and the
     * comment claiming the schema made that row impossible was wrong.
     *
     * <p>Then blank ids turned out to slip through the same CHECK ({@code '' IS NOT NULL} is true),
     * and closing that meant writing the constraint as two explicit arms — which also, as a side
     * effect nobody set out to produce, forbids a non-fix row from carrying a review at all. So the
     * original claim is true again, for a reason that had nothing to do with the original argument.
     *
     * <p><b>The filter is therefore belt-and-braces TODAY, and stops being so the day the
     * constraint is relaxed for SPEC and PLAN runs</b> — which the {@code kind} column exists to
     * allow. That relaxation must bring this test back in its counting form. Written down because
     * the honest reading of a surviving mutation is "my fixture is weak", and it took a second
     * reviewer and an unrelated fix to establish which answer was right.
     */
    @Test
    void theDatabaseRefusesANonFixRunThatCarriesAReview() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> insert("run::TEST-build-with-review", "BUILD", REVIEW, null));

        assertTrue(rootCause(refused).contains("factory_run_fix_names_its_target"),
                rootCause(refused));
    }

    /**
     * <b>What actually keeps a non-fix run out of the fix budget, and it is not the query.</b>
     *
     * <p>Dropping {@code kind = 'FIX'} from the count survives every other test here, because the
     * only non-fix row a fixture can build has a null {@code review_id} and {@code WHERE review_id =
     * ?} excludes it anyway. The filter is belt-and-braces; the V54 CHECK is the guard — it makes
     * carrying a review and not being a fix an unrepresentable state.
     *
     * <p>So this asserts the CHECK. It matters because the constraint is exactly what loosens when
     * SPEC and PLAN runs arrive (both already admitted by the same CHECK's kind list): the moment a
     * non-fix run may carry a {@code review_id}, the redundant filter stops being redundant and this
     * test is what says so.
     */
    @Test
    void theDatabaseRefusesARunThatCarriesAReviewWithoutBeingAFix() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> insert("run::TEST-liar", "BUILD", REVIEW, FINDING));

        assertTrue(rootCause(refused).contains("factory_run_fix_names_its_target"),
                rootCause(refused));
    }

    /**
     * <b>The closed kind set, which nothing asserted until now.</b>
     *
     * <p>Deleting the constraint outright left the whole module green — and it is the one V54's
     * own comment says exists so that "a typo'd literal in a writer would not pass compilation
     * and produce a row no cap counts and no filter matches". Its sibling constraint had four
     * tests; this one had none.
     */
    @Test
    void theDatabaseRefusesARunKindItDoesNotKnow() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> insert("run::TEST-typo", "BUILDD", null, null));

        assertTrue(rootCause(refused).contains("factory_run_kind_closed"), rootCause(refused));
    }

    /** And the other direction: a fix run must name what it fixes, or the cap cannot see it. */
    @Test
    void theDatabaseRefusesAFixRunThatNamesNoTarget() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> insert("run::TEST-untargeted", "FIX", null, null));

        assertTrue(rootCause(refused).contains("factory_run_fix_names_its_target"),
                rootCause(refused));
    }

    /**
     * <b>Blank is not absent, and the first version of this CHECK could not tell.</b>
     *
     * <p>{@code '' IS NOT NULL} is true in Postgres, so a biconditional written against NULL alone
     * admitted a FIX row whose ids were empty strings — matched by neither cap for any real id, so
     * the cap failed OPEN for exactly that row. Not hypothetical: this schema already uses
     * blank-not-null for {@code source_branch} and {@code dest_branch}, so a dispatcher copying a
     * blank through is an ordinary bug.
     */
    @Test
    void theDatabaseRefusesAFixRunWhoseTargetIsBlank() {
        for (String[] ids : new String[][] {{"", FINDING}, {REVIEW, ""}, {"   ", "   "}}) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> insert("run::TEST-blank", "FIX", ids[0], ids[1]),
                    ids[0] + "/" + ids[1]);
            assertTrue(rootCause(refused).contains("factory_run_fix_names_its_target"),
                    rootCause(refused));
        }
    }

    /**
     * <b>And a fix run must name the comment that asked for it.</b>
     *
     * <p>The claim V56 adds is the only thing that stops one comment buying two runs, and it is a
     * partial index on {@code comment_id IS NOT NULL} — so a FIX row without one is counted by the
     * cap and invisible to the claim. {@code asFixFor} refuses a blank, which is why this is
     * written as raw SQL: the point is that the SCHEMA holds the rule, not one careful writer.
     *
     * <p>One-directional on purpose, and the second half proves it: a BUILD run has no comment and
     * never will, so the biconditional V54 uses would be wrong here.
     */
    @Test
    void theDatabaseRefusesAFixRunThatNamesNoComment() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> insert("run::TEST-unclaimed", "FIX", REVIEW, FINDING, null));

        assertTrue(rootCause(refused).contains("factory_run_fix_names_its_comment"),
                rootCause(refused));
        assertDoesNotThrow(() -> insert("run::TEST-build-no-comment", "BUILD", null, null, null),
                "and a build run must still be writable without one");
    }

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    @Test
    void allowsAFixWhileBothCapsHaveRoom() {
        insertFixRun("run::TEST-1", REVIEW, FINDING);

        assertTrue(fixRuns.decide(REVIEW, FINDING, 2, 5).allowed());
    }

    /** The per-finding cap: this finding has had its N. */
    @Test
    void refusesWhenTheFindingHasHadItsFixRuns() {
        insertFixRun("run::TEST-1", REVIEW, FINDING);
        insertFixRun("run::TEST-2", REVIEW, FINDING);

        FixRuns.Decision decision = fixRuns.decide(REVIEW, FINDING, 2, 99);
        assertFalse(decision.allowed());
        assertTrue(decision.why().contains("this finding"), decision.why());
    }

    /**
     * The per-review cap, on findings that have each had ONE run — the exact shape the per-finding
     * axis cannot see, and the reason there are two.
     */
    @Test
    void refusesWhenTheReviewHasHadItsFixRunsAcrossDifferentFindings() {
        insertFixRun("run::TEST-1", REVIEW, "thread-1");
        insertFixRun("run::TEST-2", REVIEW, "thread-2");
        insertFixRun("run::TEST-3", REVIEW, "thread-3");

        FixRuns.Decision decision = fixRuns.decide(REVIEW, "thread-4", 2, 3);
        assertFalse(decision.allowed(), "per-finding sees zero for thread-4; per-review sees three");
        assertTrue(decision.why().contains("this pull request"), decision.why());
    }

    /**
     * Unset means unlimited, matching every other cap in this deployment (ADR-025).
     *
     * <p><b>Negative as well as zero, and only zero was ever tested.</b> The guards read
     * {@code > 0} and the javadoc says "non-positive", so changing them to {@code != 0} passed —
     * an operator writing {@code -1}, which is the usual spelling of "unlimited", would have had
     * every fix refused with the message "this finding has already had -1 fix run(s)".
     */
    @Test
    void treatsANonPositiveCapAsUnlimited() {
        insertFixRun("run::TEST-1", REVIEW, FINDING);
        insertFixRun("run::TEST-2", REVIEW, FINDING);

        assertTrue(fixRuns.decide(REVIEW, FINDING, 0, 0).allowed());
        assertTrue(fixRuns.decide(REVIEW, FINDING, -1, -1).allowed(), "a negative cap is unlimited too");
    }

    /**
     * <b>Each cap binds on its own, and every other case here sets BOTH.</b>
     *
     * <p>That shared shape hid a real defect: ANDing the two guards together —
     * {@code perFinding > 0 && perReview > 0 && ...} — passed every test in this class. An
     * operator who sets a per-finding cap and leaves the chain unlimited would have had the cap
     * they set silently disabled by the one they did not, which is the exact failure two axes
     * exist to prevent.
     */
    @Test
    void eachCapBindsOnItsOwnWhenTheOtherIsUnset() {
        insertFixRun("run::TEST-1", REVIEW, FINDING);
        insertFixRun("run::TEST-2", REVIEW, FINDING);

        assertFalse(fixRuns.decide(REVIEW, FINDING, 2, 0).allowed(),
                "the per-finding cap binds with no review cap set");
        assertFalse(fixRuns.decide(REVIEW, FINDING, 0, 2).allowed(),
                "the per-review cap binds with no finding cap set");
    }

    /**
     * The attempt a re-dispatch takes.
     *
     * <p>{@code RunIds} embeds the attempt, and {@code RunResource} pins it to 1 with a 409 on any
     * repeat — "M0 runs each subject once". FR-F32's N is unreachable while that holds, because every
     * retry of one finding would collide on the same run id.
     */
    @Test
    void countsTheNextAttemptFromTheRunsAlreadyDispatched() {
        assertEquals(1, fixRuns.nextAttempt(REVIEW, FINDING), "the first fix for a finding is attempt 1");

        insertFixRun("run::TEST-1", REVIEW, FINDING);
        assertEquals(2, fixRuns.nextAttempt(REVIEW, FINDING));

        // The PER-FINDING axis, asserted. Reading the per-review count here passed every case
        // above, because each seeded one run for one finding on one review and the two counts
        // agreed. They must not: per-review numbering would report "attempt 3" for a finding's
        // FIRST fix, contradicting the per-finding refusal message in the same class.
        insertFixRun("run::TEST-2", REVIEW, OTHER_FINDING);
        assertEquals(2, fixRuns.nextAttempt(REVIEW, FINDING),
                "another finding's run is not this finding's attempt");
    }

    // --- fixtures ---------------------------------------------------------------------------

    private void insertFixRun(String runId, String reviewId, String findingRef) {
        insert(runId, "FIX", reviewId, findingRef);
    }

    /** A fix run that started, spent and then died — written straight in, cause and all. */
    private void insertFailedFixRun(String runId, String reviewId, String findingRef, String cause) {
        insert(runId, "FIX", reviewId, findingRef);
        exec("""
                UPDATE factory_run
                   SET status = 'failed', failure_cause = ?, failure_detail = ?,
                       started_at = now(), ended_at = now()
                 WHERE run_id = ?
                """, cause, "TEST-the run executed and then failed", runId);
    }

    private void insertBuildRun(String runId) {
        insert(runId, "BUILD", null, null);
    }

    /**
     * <b>A FIX row here always carries a comment, so each CHECK is tested in isolation.</b>
     *
     * <p>V56 requires one, and without it every fix fixture in this file would fail on that
     * constraint instead of the one its test names — including the two that exist to prove
     * {@code factory_run_fix_names_its_target} bites. A test that reports the wrong constraint is
     * a test that stops noticing when its own is dropped.
     */
    private void insert(String runId, String kind, String reviewId, String findingRef) {
        insert(runId, kind, reviewId, findingRef, "FIX".equals(kind) ? runId + "-comment" : null);
    }

    private void insert(String runId, String kind, String reviewId, String findingRef,
                        String commentId) {
        exec("""
                INSERT INTO factory_run (run_id, provider_type, workspace, slug, subject, attempt,
                                         status, harness, model, base_branch, base_commit, branch,
                                         kind, review_id, finding_ref, comment_id)
                VALUES (?, 'github', 'TEST-WS', 'TEST-REPO', 'TEST-SUBJECT', 1,
                        'queued', 'codex', 'TEST-MODEL', 'main', 'TESTSHA0', 'spire/TEST-SUBJECT',
                        ?, ?, ?, ?)
                """, runId, kind, reviewId, findingRef, commentId);
    }

    private void exec(String sql, String... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }
}
