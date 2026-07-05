package dev.codespire.orchestrator.policy;

import dev.codespire.contract.scm.Author;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewPolicyTest {

    @Test
    void observeModeParsedCaseInsensitively() {
        assertTrue(new ReviewPolicy("observe", "").observeOnly());
        assertTrue(new ReviewPolicy(" OBSERVE ", "").observeOnly());
        assertFalse(new ReviewPolicy("active", "").observeOnly());
        assertFalse(new ReviewPolicy(null, "").observeOnly());
    }

    @Test
    void emptyAllowlistAllowsEveryone() {
        ReviewPolicy p = new ReviewPolicy("active", "");
        assertTrue(p.allows(Author.of("acc-1", "alice", "Alice")));
        assertTrue(p.allows(null), "no allowlist configured = review everyone");
    }

    @Test
    void allowlistMatchesByUsernameCaseInsensitive() {
        ReviewPolicy p = new ReviewPolicy("active", "Alice, bob");
        assertTrue(p.allows(Author.of("acc-1", "alice", "Alice")));
        assertTrue(p.allows(Author.of("acc-2", "BOB", "Bob")));
    }

    @Test
    void allowlistMatchesByStableAccountId() {
        ReviewPolicy p = new ReviewPolicy("active", "712020:d1005216");
        assertTrue(p.allows(Author.of("712020:d1005216", "any-nickname", "W")));
    }

    @Test
    void deniesUnlistedAuthor() {
        ReviewPolicy p = new ReviewPolicy("active", "alice");
        assertFalse(p.allows(Author.of("acc-9", "mallory", "M")));
    }

    @Test
    void deniesNullAuthorWhenAllowlistSet() {
        assertFalse(new ReviewPolicy("active", "alice").allows(null));
    }
}
