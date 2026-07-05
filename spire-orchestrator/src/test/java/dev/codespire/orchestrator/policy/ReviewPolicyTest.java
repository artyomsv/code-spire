package dev.codespire.orchestrator.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewPolicyTest {

    @Test
    void observeModeParsedCaseInsensitively() {
        assertTrue(new ReviewPolicy("observe").observeOnly());
        assertTrue(new ReviewPolicy(" OBSERVE ").observeOnly());
    }

    @Test
    void activeIsNotObserve() {
        assertFalse(new ReviewPolicy("active").observeOnly());
        assertFalse(new ReviewPolicy((String) null).observeOnly());
    }
}
