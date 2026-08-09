package dev.codespire.orchestrator.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The deterministic call_ref that makes charge recording idempotent under redelivery. A blank
 * component must be rejected outright: {@code "x||REVIEW"} still looks well-formed, so letting it
 * through would silently collide two different calls into the same {@code UNIQUE (call_ref, token_type)}
 * identity and lose the second charge as a false "redelivery".
 */
class CallRefsTest {

    @Test
    void composesReviewIdSlotAndKindWithPipes() {
        assertEquals("review::TEST-WS/TEST-REPO#1|abc123|REVIEW",
                CallRefs.of("review::TEST-WS/TEST-REPO#1", "abc123", ChargeKind.REVIEW));
    }

    /**
     * Run 1 keeps the bare commit deliberately: a review that never re-ran must reproduce the ref its
     * charges were already written under, so replaying its result yields its own row rather than a
     * second one. Only a re-run needs a new identity, because only a re-run spends again.
     */
    @Test
    void theFirstRunOfACommitIsSlottedByTheCommitAlone() {
        assertEquals("abc123", CallRefs.reviewSlot("abc123", ReviewRuns.FIRST_RUN));
    }

    @Test
    void aLaterRunOfTheSameCommitGetsItsOwnSlot() {
        assertEquals("abc123#run2", CallRefs.reviewSlot("abc123", 2));
        assertNotEquals(CallRefs.reviewSlot("abc123", 2), CallRefs.reviewSlot("abc123", 3),
                "each re-run is a separate paid call and needs a separate identity");
    }

    @Test
    void rejectsABlankReviewId() {
        assertThrows(IllegalArgumentException.class, () -> CallRefs.of("", "abc123", ChargeKind.REVIEW));
        assertThrows(IllegalArgumentException.class, () -> CallRefs.of(null, "abc123", ChargeKind.REVIEW));
    }

    @Test
    void rejectsABlankSlot() {
        assertThrows(IllegalArgumentException.class,
                () -> CallRefs.of("review::TEST-WS/TEST-REPO#1", "", ChargeKind.REVIEW));
        assertThrows(IllegalArgumentException.class,
                () -> CallRefs.of("review::TEST-WS/TEST-REPO#1", null, ChargeKind.REVIEW));
    }
}
