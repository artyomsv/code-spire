package dev.codespire.orchestrator.review.checks;

import java.time.Duration;
import java.time.Instant;

/**
 * The rule of "Start review: after checks finish" (docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md).
 * Pure, so every branch is testable without a database or a forge.
 *
 * <ul>
 *   <li>Green starts the review; red skips it (no review, no charge).</li>
 *   <li>No checks starts it once the {@link #GRACE} read is due, so checks that register a few seconds late are seen
 *   and a repository without CI never waits long.</li>
 *   <li>Anything still pending, or unreadable, after {@link #TIMEOUT} starts a review that never approves.</li>
 * </ul>
 */
public final class ChecksWaitDecision {

    /** How long a head with no checks at all is given for its checks to register. */
    public static final Duration GRACE = Duration.ofSeconds(60);
    /** How long a review waits for pending checks before it starts anyway. */
    public static final Duration TIMEOUT = Duration.ofMinutes(60);
    /** The fallback read's interval, for a wait whose webhook never arrives. */
    public static final Duration SWEEP = Duration.ofMinutes(5);

    public enum Action { KEEP_WAITING, START, START_UNFINISHED, SKIP }

    private ChecksWaitDecision() {
    }

    public static Action decide(ChecksWait wait, ChecksVerdict verdict, Instant now) {
        return switch (verdict.kind()) {
            case GREEN -> Action.START;
            case RED -> Action.SKIP;
            case NO_CHECKS -> now.isBefore(wait.waitingSince().plus(GRACE)) ? Action.KEEP_WAITING : Action.START;
            case PENDING, UNREADABLE -> now.isBefore(wait.waitingSince().plus(TIMEOUT))
                    ? Action.KEEP_WAITING : Action.START_UNFINISHED;
        };
    }
}
