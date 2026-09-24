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
        return new HarnessSignInCommand.Start("TEST-sign-in", "codex", "TEST-image", 840, requestedAt, 240);
    }

    /** A unit may be opened only if it can print its code before the start window closes. */
    @Test
    void aUnitMayBeOpenedOnlyWhileItsCodeCanStillBeShownInTime() {
        Duration toPrompt = Duration.ofSeconds(60);
        assertEquals(true, start(PRESSED).mayOpenAt(PRESSED.plusSeconds(180), toPrompt));
        assertEquals(false, start(PRESSED).mayOpenAt(PRESSED.plusSeconds(181), toPrompt),
                "the wait is far from over, but the code would land after the window");
    }

    @Test
    void aStartWithNoWindowOrNoPressOpensNothing() {
        assertEquals(false, new HarnessSignInCommand.Start("TEST-sign-in", "codex", "TEST-image", 840, PRESSED, 0)
                .mayOpenAt(PRESSED, Duration.ZERO));
        assertEquals(false, start(null).mayOpenAt(PRESSED, Duration.ZERO));
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
