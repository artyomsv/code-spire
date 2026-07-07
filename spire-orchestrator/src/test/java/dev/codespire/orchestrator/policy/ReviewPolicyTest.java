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
}
