package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.port.PullRequestLander.CheckSummary;
import dev.codespire.contract.port.PullRequestLander.Checks;
import dev.codespire.contract.port.PullRequestLander.FailedCheck;
import dev.codespire.contract.port.PullRequestLander.LandState;
import dev.codespire.orchestrator.review.checks.ChecksWaitDecision.Action;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pure rule of "Start review: after checks finish": the verdict a read gives, and what each verdict does. */
class ChecksWaitDecisionTest {

    static final String HEAD = "a".repeat(40);
    private static final Instant SINCE = Instant.parse("1970-01-01T00:00:00Z");
    private static final ChecksWait WAIT = new ChecksWait("TEST-review", UUID.randomUUID(), HEAD, false, SINCE);

    @Test
    void greenStartsAndRedSkipsAtOnce() {
        assertEquals(Action.START, decide(Checks.PASSED, 3, 3, SINCE));
        assertEquals(Action.SKIP, decide(Checks.FAILED, 3, 3, SINCE));
    }

    /** Only green starts: a cancelled, timed-out or errored check is not a pass. */
    @Test
    void aCheckThatIsNotCodeIsRedToo() {
        assertEquals(Action.SKIP, decide(Checks.NOT_CODE, 2, 2, SINCE));
    }

    /** The reduction ranks FAILED above PENDING; a failure among unfinished checks still waits for the rest. */
    @Test
    void aFailureWhileOtherChecksRunKeepsWaiting() {
        assertEquals(Action.KEEP_WAITING, decide(Checks.FAILED, 2, 3, SINCE.plusSeconds(120)));
    }

    @Test
    void noChecksWaitsForTheGraceReadThenStarts() {
        assertEquals(Action.KEEP_WAITING, decide(Checks.PASSED, 0, 0, SINCE.plusSeconds(59)));
        assertEquals(Action.START, decide(Checks.PASSED, 0, 0, SINCE.plus(ChecksWaitDecision.GRACE)));
    }

    @Test
    void pendingChecksStartAnUnfinishedReviewOnlyAtTheTimeout() {
        assertEquals(Action.KEEP_WAITING, decide(Checks.PENDING, 1, 2, SINCE.plusSeconds(3599)));
        assertEquals(Action.START_UNFINISHED, decide(Checks.PENDING, 1, 2, SINCE.plus(ChecksWaitDecision.TIMEOUT)));
    }

    @Test
    void anUnreadableForgeWaitsToTheTimeoutAndNeverSkips() {
        assertEquals(Action.KEEP_WAITING, ChecksWaitDecision.decide(WAIT, ChecksVerdict.UNREADABLE, SINCE));
        assertEquals(Action.START_UNFINISHED, ChecksWaitDecision.decide(WAIT, ChecksVerdict.UNREADABLE,
                SINCE.plus(ChecksWaitDecision.TIMEOUT)));
    }

    /** A pull request whose head moved on is not this wait's head: the new head's event replaces the wait. */
    @Test
    void aMovedHeadIsPendingNotGreen() {
        LandState moved = pullRequest("b".repeat(40), new CheckSummary(Checks.PASSED, 1, 1, List.of()));
        assertEquals(ChecksVerdict.Kind.PENDING, ChecksVerdict.of(moved, HEAD).kind());
    }

    @Test
    void redNamesTheFailedChecks() {
        LandState red = pullRequest(HEAD, new CheckSummary(Checks.FAILED, 2, 2,
                List.of(new FailedCheck("TEST-build", "failure", "", "", List.of()))));
        assertEquals(List.of("TEST-build"), ChecksVerdict.of(red, HEAD).failed());
    }

    /** A history the reader could not read in full proves no failure, so it waits rather than skipping. */
    @Test
    void anIncompleteHistoryIsPending() {
        LandState incomplete = pullRequest(HEAD, new CheckSummary(Checks.NOT_CODE, 5, 5,
                List.of(new FailedCheck("check history", ChecksVerdict.HISTORY_INCOMPLETE, "", "", List.of()))));
        assertEquals(ChecksVerdict.Kind.PENDING, ChecksVerdict.of(incomplete, HEAD).kind());
    }

    private static Action decide(Checks state, int done, int total, Instant now) {
        LandState read = pullRequest(HEAD, new CheckSummary(state, done, total, List.of()));
        return ChecksWaitDecision.decide(WAIT, ChecksVerdict.of(read, HEAD), now);
    }

    static LandState pullRequest(String head, CheckSummary checks) {
        return new LandState("open", false, null, null, head, "TEST-feature", "main", "c".repeat(40), null, null,
                checks);
    }
}
