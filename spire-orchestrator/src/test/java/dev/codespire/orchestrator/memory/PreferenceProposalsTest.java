package dev.codespire.orchestrator.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The threshold arithmetic that decides whether a preference is ever proposed (P4 / FR-10).
 *
 * <p>Plain unit tests, no database: {@code qualifies} is the whole decision, and it is the one place
 * a rounding slip or a flipped comparison would silently lower the bar for every future proposal.
 */
class PreferenceProposalsTest {

    @Test
    void aGroupBelowTheEvidenceFloorNeverQualifies() {
        PreferenceProposals proposals = withThresholds(10, 75);

        assertFalse(proposals.qualifies(9, 9, 5), "nine unanimous dismissals is still under the floor");
        assertTrue(proposals.qualifies(10, 10, 5));
    }

    /** The percentage is exact at the boundary rather than a whisker under it. */
    @Test
    void theShareIsInclusiveAtExactlyTheThreshold() {
        PreferenceProposals proposals = withThresholds(10, 75);

        assertTrue(proposals.qualifies(20, 15, 5), "15 of 20 is exactly 75%");
        assertFalse(proposals.qualifies(20, 14, 5), "14 of 20 is 70%");
    }

    /**
     * The distinct-review floor, which is the cheap half of the answer to manufactured evidence.
     *
     * <p>An {@code ACKNOWLEDGED} verdict comes from the reconcile model reading the pull request
     * author's OWN replies, so ten "won't fix" answers on one pull request are evidence the person
     * who benefits from it produced. Spanning two reviews does not make it trustworthy — that is what
     * the admin gate and the visible count are for — but it stops the cheapest version outright.
     */
    @Test
    void aGroupFromASinglePullRequestNeverQualifiesHoweverUnanimous() {
        PreferenceProposals proposals = withThresholds(10, 75);

        assertFalse(proposals.qualifies(50, 50, 1), "fifty dismissals on one PR is one person's opinion");
        assertTrue(proposals.qualifies(10, 10, PreferenceProposals.MIN_DISTINCT_REVIEWS));
    }

    /** Integer arithmetic, so the comparison must not lose the remainder. */
    @Test
    void theShareIsComputedWithoutIntegerTruncation() {
        PreferenceProposals proposals = withThresholds(3, 66);

        // 2 of 3 is 66.67%, which clears a 66% bar. Computing (dismissed / judged) * 100 in ints
        // would floor to 0 and refuse every group ever.
        assertTrue(proposals.qualifies(3, 2, 2));
    }

    /**
     * The never-suppressed floor is a set, not a comment. A category or severity added to the list
     * has to reach both the proposal scan and {@code Preference.covers}, so the sets are the shared
     * source both read.
     */
    @Test
    void securityAndBlockerAreOnTheNeverSuppressedFloor() {
        assertTrue(PreferenceProposals.NEVER_SUPPRESSED_CATEGORIES.contains("SECURITY"));
        assertTrue(PreferenceProposals.NEVER_SUPPRESSED_SEVERITIES.contains("BLOCKER"));
    }

    private static PreferenceProposals withThresholds(int minEvidence, int minDismissedPercent) {
        PreferenceProposals proposals = new PreferenceProposals();
        proposals.minEvidence = minEvidence;
        proposals.minDismissedPercent = minDismissedPercent;
        return proposals;
    }
}
