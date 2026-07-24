package dev.codespire.orchestrator.readmodel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The loc->thread index keeps the NEWEST comment id per anchor (SCM comment ids are monotonic), so a
 * finding re-posted at the same location across re-review rounds reconciles against its CURRENT
 * thread — not a stale, already-resolved earlier one (the "outdated instead of resolved" bug).
 */
class ReviewProjectionThreadRefTest {

    @Test
    void keepsTheLargerMonotonicCommentId() {
        assertEquals("3648554983", ReviewProjection.newerThreadRef("3610391801", "3648554983"));
        assertEquals("3648554983", ReviewProjection.newerThreadRef("3648554983", "3610391801"));
    }

    @Test
    void comparesNumericallyNotLexically() {
        // "9" < "10" numerically, but lexically "9" > "10" — must pick 10.
        assertEquals("10", ReviewProjection.newerThreadRef("9", "10"));
        assertEquals("10", ReviewProjection.newerThreadRef("10", "9"));
    }

    @Test
    void keepsFirstSeenWhenNonNumeric() {
        assertEquals("abc", ReviewProjection.newerThreadRef("abc", "def"));
    }
}
