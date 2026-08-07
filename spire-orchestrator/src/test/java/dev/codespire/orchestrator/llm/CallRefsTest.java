package dev.codespire.orchestrator.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
