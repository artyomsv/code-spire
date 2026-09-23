package dev.codespire.contract.command;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A start's wait is counted from the operator's press, not from delivery, because a start can be
 * replayed or re-sent (review of PR #168).
 */
class HarnessSignInStartTest {

    private static final Instant PRESSED = Instant.parse("2026-09-23T07:00:00Z");

    private static HarnessSignInCommand.Start start(Instant requestedAt) {
        return new HarnessSignInCommand.Start("TEST-sign-in", "codex", "TEST-image", 840, requestedAt);
    }

    @Test
    void aLateStartGetsOnlyWhatIsLeft() {
        assertEquals(Duration.ofSeconds(240), start(PRESSED).remainingWait(PRESSED.plusSeconds(600)));
    }

    @Test
    void aStartWhoseWaitIsGoneGetsNothing() {
        assertEquals(Duration.ZERO, start(PRESSED).remainingWait(PRESSED.plusSeconds(900)));
    }

    /** A clock behind the orchestrator's must not grant more than the full wait. */
    @Test
    void neverMoreThanTheFullWait() {
        assertEquals(Duration.ofSeconds(840), start(PRESSED).remainingWait(PRESSED.minusSeconds(60)));
    }

    @Test
    void aStartFromBeforeTimesExistedGetsTheFullWait() {
        assertEquals(Duration.ofSeconds(840), start(null).remainingWait(PRESSED));
    }
}
