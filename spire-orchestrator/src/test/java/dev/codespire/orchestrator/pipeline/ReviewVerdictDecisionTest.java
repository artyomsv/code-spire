package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.ReviewVerdict;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The verdict rule (ADR-047 §4.1): findings decide the assessment, the repository decides what is posted. */
class ReviewVerdictDecisionTest {

    private static final Optional<Boolean> ON = Optional.of(true);

    @Test
    void degradedGivesComment() {
        var decision = ReviewVerdictDecision.decide(true, 3, ON, true);
        assertEquals(ReviewVerdict.COMMENT, decision.assessment());
        assertEquals(ReviewVerdict.COMMENT, decision.verdict());
        assertTrue(decision.mayApprove(), "the setting is on; the hold stays (D5)");
        assertEquals("degraded", decision.reason());
    }

    @Test
    void anOpenBlockerGivesRequestChanges() {
        var decision = ReviewVerdictDecision.decide(false, 1, ON, true);
        assertEquals(ReviewVerdict.REQUEST_CHANGES, decision.verdict());
        assertEquals("open_blockers=1", decision.reason());
    }

    @Test
    void noBlockersGiveApprove() {
        var decision = ReviewVerdictDecision.decide(false, 0, ON, true);
        assertEquals(ReviewVerdict.APPROVE, decision.verdict());
        assertEquals("no_open_blockers", decision.reason());
    }

    @Test
    void theSettingOffGivesCommentWithAssessmentApprove() {
        var decision = ReviewVerdictDecision.decide(false, 0, Optional.of(false), true);
        assertEquals(ReviewVerdict.APPROVE, decision.assessment());
        assertEquals(ReviewVerdict.COMMENT, decision.verdict());
        assertFalse(decision.mayApprove());
        assertEquals("setting_off", decision.reason());
        assertEquals("verdict:COMMENT · assessment:APPROVE · setting_off", decision.detail());
    }

    @Test
    void anUnknownRepositoryGivesComment() {
        var decision = ReviewVerdictDecision.decide(false, 2, Optional.empty(), true);
        assertEquals(ReviewVerdict.REQUEST_CHANGES, decision.assessment());
        assertEquals(ReviewVerdict.COMMENT, decision.verdict());
        assertEquals("repository_unknown", decision.reason());
    }

    @Test
    void aReviewerWithoutIdentityGivesComment() {
        var decision = ReviewVerdictDecision.decide(false, 0, ON, false);
        assertEquals(ReviewVerdict.COMMENT, decision.verdict());
        assertFalse(decision.mayApprove());
        assertEquals("reviewer_identity_unknown", decision.reason());
    }
}
