package dev.codespire.contract.event;

import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The reviewId IS the address (finding H2): parse must be a lossless inverse, never a fallback. */
class ReviewIdsTest {

    @Test
    void parseIsTheInverseOfReviewId() {
        RepoRef repo = new RepoRef("sandbox", "demo-repo");
        ReviewIds.Parsed parsed = ReviewIds.parse(ReviewIds.reviewId(repo, 42));
        assertEquals(repo, parsed.repo());
        assertEquals(42, parsed.prId());
    }

    @Test
    void slugsWithDotsAndDashesSurvive() {
        RepoRef repo = new RepoRef("my.workspace", "repo-name.v2");
        ReviewIds.Parsed parsed = ReviewIds.parse(ReviewIds.reviewId(repo, 7));
        assertEquals(repo, parsed.repo());
        assertEquals(7, parsed.prId());
    }

    @Test
    void malformedIdsThrowInsteadOfFallingBack() {
        assertThrows(IllegalArgumentException.class, () -> ReviewIds.parse(null));
        assertThrows(IllegalArgumentException.class, () -> ReviewIds.parse("not-a-review-id"));
        assertThrows(IllegalArgumentException.class, () -> ReviewIds.parse("review::no-slash#1"));
        assertThrows(IllegalArgumentException.class, () -> ReviewIds.parse("review::ws/slug-no-hash"));
        assertThrows(NumberFormatException.class, () -> ReviewIds.parse("review::ws/slug#not-a-number"));
    }
}
