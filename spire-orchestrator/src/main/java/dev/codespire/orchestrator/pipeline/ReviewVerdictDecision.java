package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.ReviewVerdict;

import java.util.Optional;

/**
 * The verdict rule (ADR-047 §4.1). Pure, so the rule is testable without the projection: what the
 * findings say is the assessment; whether the repository lets the bot change merge state decides what
 * is posted.
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

    private ReviewVerdictDecision() {
    }

    /**
     * @param setting the repository's "reviewer may approve"; empty when the repository is unknown
     * @param reviewerIdentityKnown whether the reviewer registration has a bot identity — without one the
     *     land gate could not tell the bot's own Approve from a human's (spec §1)
     */
    public static Decision decide(boolean degraded, int openBlockers, Optional<Boolean> setting, boolean reviewerIdentityKnown) {
        ReviewVerdict assessment = degraded ? ReviewVerdict.COMMENT
                : openBlockers > 0 ? ReviewVerdict.REQUEST_CHANGES : ReviewVerdict.APPROVE;
        String reason = degraded ? REASON_DEGRADED : openBlockers > 0 ? "open_blockers=" + openBlockers : REASON_NO_BLOCKERS;
        boolean mayApprove = true;
        if (!setting.orElse(true)) {
            mayApprove = false;
            reason = REASON_SETTING_OFF;
        } else if (!reviewerIdentityKnown) {
            mayApprove = false;
            reason = REASON_IDENTITY_UNKNOWN;
        }
        return new Decision(assessment, mayApprove ? assessment : ReviewVerdict.COMMENT, mayApprove, reason);
    }
}
