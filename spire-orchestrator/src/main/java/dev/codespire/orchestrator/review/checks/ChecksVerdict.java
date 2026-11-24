package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.port.PullRequestLander.CheckSummary;
import dev.codespire.contract.port.PullRequestLander.Checks;
import dev.codespire.contract.port.PullRequestLander.FailedCheck;
import dev.codespire.contract.port.PullRequestLander.LandState;

import java.util.List;

/**
 * What one read of a waiting head's checks says (review waits for CI). The read is the land path's
 * ({@code GitHubCheckReduction}): commit statuses plus check runs, every check counted.
 *
 * <ul>
 *   <li>{@link Kind#PENDING}: a check has not finished, or the pull request's head is no longer the waited one (the new
 *   head's own event replaces the wait).</li>
 *   <li>{@link Kind#NO_CHECKS}: the head has no status and no check run.</li>
 *   <li>{@link Kind#GREEN}: every check finished and passed.</li>
 *   <li>{@link Kind#RED}: every check finished and at least one did not pass ({@code failed} names them). A cancelled,
 *   timed-out or errored check is red too: only green starts a review.</li>
 *   <li>{@link Kind#UNREADABLE}: the forge could not be read; the wait goes on until the timeout.</li>
 * </ul>
 */
public record ChecksVerdict(Kind kind, List<String> failed) {

    public enum Kind { PENDING, NO_CHECKS, GREEN, RED, UNREADABLE }

    public static final ChecksVerdict PENDING = new ChecksVerdict(Kind.PENDING, List.of());
    public static final ChecksVerdict UNREADABLE = new ChecksVerdict(Kind.UNREADABLE, List.of());
    /** The land reader's mark for a check history it could not read in full (GitHubPullRequestLander). */
    static final String HISTORY_INCOMPLETE = "history_incomplete";

    public ChecksVerdict {
        failed = List.copyOf(failed);
    }

    /** The verdict on {@code head} from a read of its pull request. */
    public static ChecksVerdict of(LandState pullRequest, String head) {
        CheckSummary checks = pullRequest.checks();
        if (!head.equals(pullRequest.headSha()) || checks == null) return PENDING;
        if (checks.total() == 0) return new ChecksVerdict(Kind.NO_CHECKS, List.of());
        if (checks.done() < checks.total() || incomplete(checks)) return PENDING;
        if (checks.state() == Checks.PASSED) return new ChecksVerdict(Kind.GREEN, List.of());
        return new ChecksVerdict(Kind.RED, checks.failed().stream().map(FailedCheck::name).toList());
    }

    /** More checks than one read returns: nothing is known to have failed, so the wait goes on to its timeout. */
    private static boolean incomplete(CheckSummary checks) {
        return checks.failed().stream().anyMatch(check -> HISTORY_INCOMPLETE.equals(check.conclusion()));
    }
}
