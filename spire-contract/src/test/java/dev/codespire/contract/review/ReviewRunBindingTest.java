package dev.codespire.contract.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A review run's binding names its whole call: the review, the kind, the pull request, the head and the slot. */
class ReviewRunBindingTest {

    private static final String HEAD = "a".repeat(40);

    @Test
    void aReviewRunBindingRefusesABlankReviewIdOrCommit() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewRunBinding(" ", ReviewRunBinding.Kind.REVIEW, 7, HEAD, "TEST-slot"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewRunBinding(null, ReviewRunBinding.Kind.REVIEW, 7, HEAD, "TEST-slot"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.REVIEW, 7, " ", "TEST-slot"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.REVIEW, 7, null, "TEST-slot"));
        assertDoesNotThrow(() -> new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.REVIEW, 7, HEAD, "TEST-slot"));
    }

    @Test
    void aReviewRunBindingNamesItsKindPullRequestAndSlot() {
        assertThrows(NullPointerException.class,
                () -> new ReviewRunBinding("TEST-review", null, 7, HEAD, "TEST-slot"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.RECONCILE, 0, HEAD, "TEST-slot"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.FOLLOWUP, 7, HEAD, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.FOLLOWUP, 7, HEAD, null));
    }
}
