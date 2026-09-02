package dev.codespire.harness;

import dev.codespire.contract.review.TokenType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TokenBucket} and the ledger's {@code TokenType} must map one-to-one, by name.
 *
 * <p>They are deliberately two enums — the factory tier does not inherit the review domain — and the
 * price of that separation is a translation step in the worker, sitting exactly where ADR-023's
 * failures lived. A constant added to one and not the other does not fail to compile: it fails at the
 * translation site, at runtime, by mapping to the wrong bucket or to nothing, and the symptom is a
 * wrong number in a ledger rather than an exception anybody sees.
 *
 * <p>spire-contract is on this module's TEST classpath only. There is no production dependency, so
 * the tier separation the separate enum exists for survives — this test is the whole cost of it.
 */
class TokenBucketMatchesLedgerDimensionsTest {

    @Test
    void theHarnessBucketsAreExactlyTheLedgerDimensions() {
        Set<String> harness = names(TokenBucket.values());
        Set<String> ledger = names(TokenType.values());

        assertEquals(ledger, harness,
                "TokenBucket and TokenType have drifted. They are translated one-to-one by name in "
                        + "the worker, so a constant present in only one maps to the wrong bucket or "
                        + "to nothing — silently, and only visible as a wrong number in the ledger. "
                        + "Add the constant to both, or delete it from both.");
    }

    /** Guards the guard: an empty-to-empty comparison would pass while checking nothing. */
    @Test
    void bothEnumsAreNonEmpty() {
        assertTrue(TokenBucket.values().length >= 6, "TokenBucket looks truncated");
        assertTrue(TokenType.values().length >= 6, "TokenType looks truncated");
    }

    private static Set<String> names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }
}
