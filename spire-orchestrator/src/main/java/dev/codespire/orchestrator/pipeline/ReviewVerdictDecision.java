package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.orchestrator.repository.BlockApprovalAt;

import java.util.Locale;

/**
 * The verdict rule (ADR-047 §4.1). Pure, so the rule is testable without the projection: what the
 * findings say is the assessment; whether the repository lets the bot change merge state decides what
 * is posted. Which findings block is the repository's "Block approval at" (V115).
 */
public final class ReviewVerdictDecision {

    public static final String REASON_DEGRADED = "degraded";
    public static final String REASON_NO_BLOCKERS = "no_open_blockers";
    public static final String REASON_SETTING_OFF = "setting_off";
    public static final String REASON_REPOSITORY_UNKNOWN = "repository_unknown";
    public static final String REASON_IDENTITY_UNKNOWN = "reviewer_identity_unknown";

    public record Decision(ReviewVerdict assessment, ReviewVerdict verdict, boolean mayApprove, String reason) {
        /** The timeline line: what is posted, what the findings said, and why they differ when they do. */
        public String detail() {
            return "verdict:" + verdict + " · assessment:" + assessment + " · " + reason;
        }
    }

    /**
     * What the rule decides from.
     *
     * @param openBlocking the open findings at or above {@code blockAt}
     * @param blockAt the repository's "Block approval at"; null is {@link BlockApprovalAt#DEFAULT}, as for an unknown
     *     repository
     * @param reviewerMayApprove the repository's "reviewer may approve"; null when the repository is unknown
     * @param reviewerIdentityKnown whether the reviewer registration has a bot identity — without one the
     *     land gate could not tell the bot's own Approve from a human's (spec §1)
     */
    public record VerdictFacts(boolean degraded, int openBlocking, BlockApprovalAt blockAt, Boolean reviewerMayApprove,
                               boolean reviewerIdentityKnown) {
        BlockApprovalAt level() {
            return blockAt == null ? BlockApprovalAt.DEFAULT : blockAt;
        }
    }

    private ReviewVerdictDecision() {
    }

    public static Decision decide(VerdictFacts facts) {
        boolean blocked = facts.openBlocking() > 0;
        ReviewVerdict assessment = facts.degraded() ? ReviewVerdict.COMMENT
                : blocked ? ReviewVerdict.REQUEST_CHANGES : ReviewVerdict.APPROVE;
        String why = facts.degraded() ? REASON_DEGRADED : openReason(facts);
        String hold = holdReason(facts);
        boolean mayApprove = hold == null;
        // Why the assessment, then why it is not posted: the blocker count is not lost to the hold (M-8).
        String reason = mayApprove ? why : why + " · " + hold;
        return new Decision(assessment, mayApprove ? assessment : ReviewVerdict.COMMENT, mayApprove, reason);
    }

    /**
     * The count and the level it was taken at. BLOCKER keeps the words the timeline used before the setting existed
     * ({@code open_blockers=N}, {@code no_open_blockers}); another level names itself, so an operator can see why a
     * MAJOR held a pull request ({@code open_at_or_above_major=N}, {@code no_open_at_or_above_major}).
     */
    private static String openReason(VerdictFacts facts) {
        BlockApprovalAt level = facts.level();
        if (level == BlockApprovalAt.BLOCKER) {
            return facts.openBlocking() > 0 ? "open_blockers=" + facts.openBlocking() : REASON_NO_BLOCKERS;
        }
        String counted = "open_at_or_above_" + level.name().toLowerCase(Locale.ROOT);
        return facts.openBlocking() > 0 ? counted + "=" + facts.openBlocking() : "no_" + counted;
    }

    /** Why the bot may not change merge state here; null when it may. */
    private static String holdReason(VerdictFacts facts) {
        if (facts.reviewerMayApprove() == null) return REASON_REPOSITORY_UNKNOWN;
        if (!facts.reviewerMayApprove()) return REASON_SETTING_OFF;
        if (!facts.reviewerIdentityKnown()) return REASON_IDENTITY_UNKNOWN;
        return null;
    }
}
