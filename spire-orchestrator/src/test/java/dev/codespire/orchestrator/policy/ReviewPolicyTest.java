package dev.codespire.orchestrator.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pure mode-normalization used by {@link ReviewPolicy}. The DB-backed
 * read/toggle (stored override vs seed default) is covered by
 * {@code ReviewModeResourceTest} against a real datasource.
 */
class ReviewPolicyTest {

    @Test
    void observeParsedCaseInsensitively() {
        assertEquals(ReviewPolicy.OBSERVE, ReviewPolicy.normalize("observe"));
        assertEquals(ReviewPolicy.OBSERVE, ReviewPolicy.normalize(" OBSERVE "));
    }

    @Test
    void anythingElseIsActive() {
        assertEquals(ReviewPolicy.ACTIVE, ReviewPolicy.normalize("active"));
        assertEquals(ReviewPolicy.ACTIVE, ReviewPolicy.normalize(null));
        assertEquals(ReviewPolicy.ACTIVE, ReviewPolicy.normalize("bogus"));
    }

    @Test
    void backoffIsClampedToAUsableRange() {
        assertEquals(5_000L, ReviewPolicy.clampBackoffBase(5_000L));
        assertEquals(0L, ReviewPolicy.clampBackoffBase(-1L), "negative would schedule in the past");
        assertEquals(ReviewPolicy.MAX_BACKOFF_MS, ReviewPolicy.clampBackoffBase(Long.MAX_VALUE));
        assertEquals(2d, ReviewPolicy.clampBackoffFactor(2d));
        assertEquals(ReviewPolicy.MIN_BACKOFF_FACTOR, ReviewPolicy.clampBackoffFactor(0.1d),
                "a factor below 1 would shrink the wait on each attempt");
        assertEquals(ReviewPolicy.MAX_BACKOFF_FACTOR, ReviewPolicy.clampBackoffFactor(99d));
    }

    @Test
    void attemptBudgetIsClampedToAUsableRange() {
        // 1 means "never retry"; the ceiling stops a typo parking a review on a dead provider, since
        // every attempt re-runs the pipeline from the diff fetch.
        assertEquals(3, ReviewPolicy.clampAttempts(3));
        assertEquals(ReviewPolicy.MIN_ATTEMPTS, ReviewPolicy.clampAttempts(0));
        assertEquals(ReviewPolicy.MIN_ATTEMPTS, ReviewPolicy.clampAttempts(-5));
        assertEquals(ReviewPolicy.MAX_ATTEMPTS, ReviewPolicy.clampAttempts(999));
    }
}
