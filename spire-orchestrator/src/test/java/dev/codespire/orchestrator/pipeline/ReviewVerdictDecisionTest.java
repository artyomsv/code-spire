package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.orchestrator.pipeline.ReviewVerdictDecision.VerdictFacts;
import dev.codespire.orchestrator.repository.BlockApprovalAt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The verdict rule (ADR-047 §4.1): findings decide the assessment, the repository decides what is posted. */
class ReviewVerdictDecisionTest {

    private static ReviewVerdictDecision.Decision decide(int openBlocking, BlockApprovalAt level) {
        return ReviewVerdictDecision.decide(new VerdictFacts(false, openBlocking, level, true, true));
    }

    @Test
    void degradedGivesComment() {
        ReviewVerdictDecision.Decision decision = ReviewVerdictDecision.decide(new VerdictFacts(true, 3, BlockApprovalAt.BLOCKER, true, true));
        assertEquals(ReviewVerdict.COMMENT, decision.assessment());
        assertEquals(ReviewVerdict.COMMENT, decision.verdict());
        assertTrue(decision.mayApprove(), "the setting is on; the hold stays (D5)");
        assertEquals("degraded", decision.reason());
    }

    @Test
    void anOpenBlockerGivesRequestChanges() {
        ReviewVerdictDecision.Decision decision = decide(1, BlockApprovalAt.BLOCKER);
        assertEquals(ReviewVerdict.REQUEST_CHANGES, decision.verdict());
        assertEquals("open_blockers=1", decision.reason());
    }

    @Test
    void noBlockersGiveApprove() {
        ReviewVerdictDecision.Decision decision = decide(0, BlockApprovalAt.BLOCKER);
        assertEquals(ReviewVerdict.APPROVE, decision.verdict());
        assertEquals("no_open_blockers", decision.reason());
    }

    @Test
    void anOpenFindingAtMajorGivesRequestChangesAndNamesTheLevel() {
        ReviewVerdictDecision.Decision decision = decide(2, BlockApprovalAt.MAJOR);
        assertEquals(ReviewVerdict.REQUEST_CHANGES, decision.verdict());
        assertEquals("open_at_or_above_major=2", decision.reason());
        assertEquals("verdict:REQUEST_CHANGES · assessment:REQUEST_CHANGES · open_at_or_above_major=2", decision.detail());
    }

    @Test
    void nothingOpenAtMinorGivesApproveAndNamesTheLevel() {
        ReviewVerdictDecision.Decision decision = decide(0, BlockApprovalAt.MINOR);
        assertEquals(ReviewVerdict.APPROVE, decision.verdict());
        assertEquals("no_open_at_or_above_minor", decision.reason());
    }

    @Test
    void aLevelNotGivenIsBlocker() {
        assertEquals("open_blockers=1", decide(1, null).reason(), "an unknown repository blocks at BLOCKER");
    }

    @Test
    void theSettingOffGivesCommentWithAssessmentApprove() {
        ReviewVerdictDecision.Decision decision = ReviewVerdictDecision.decide(new VerdictFacts(false, 0, BlockApprovalAt.BLOCKER, false, true));
        assertEquals(ReviewVerdict.APPROVE, decision.assessment());
        assertEquals(ReviewVerdict.COMMENT, decision.verdict());
        assertFalse(decision.mayApprove());
        assertEquals("no_open_blockers · setting_off", decision.reason(), "why the assessment, then why it is not posted (M-8)");
        assertEquals("verdict:COMMENT · assessment:APPROVE · no_open_blockers · setting_off", decision.detail());
    }

    @Test
    void theSettingOffStillWinsAtMajor() {
        ReviewVerdictDecision.Decision decision = ReviewVerdictDecision.decide(new VerdictFacts(false, 1, BlockApprovalAt.MAJOR, false, true));
        assertEquals(ReviewVerdict.REQUEST_CHANGES, decision.assessment());
        assertEquals(ReviewVerdict.COMMENT, decision.verdict(), "the bot never changes merge state with the setting off");
        assertEquals("open_at_or_above_major=1 · setting_off", decision.reason());
    }

    @Test
    void anUnknownRepositoryGivesComment() {
        ReviewVerdictDecision.Decision decision = ReviewVerdictDecision.decide(new VerdictFacts(false, 2, BlockApprovalAt.BLOCKER, null, true));
        assertEquals(ReviewVerdict.REQUEST_CHANGES, decision.assessment());
        assertEquals(ReviewVerdict.COMMENT, decision.verdict());
        assertEquals("open_blockers=2 · repository_unknown", decision.reason(), "the blocker count is not lost (M-8)");
    }

    @Test
    void aReviewerWithoutIdentityGivesComment() {
        ReviewVerdictDecision.Decision decision = ReviewVerdictDecision.decide(new VerdictFacts(false, 0, BlockApprovalAt.BLOCKER, true, false));
        assertEquals(ReviewVerdict.COMMENT, decision.verdict());
        assertFalse(decision.mayApprove());
        assertEquals("no_open_blockers · reviewer_identity_unknown", decision.reason());
    }
}
