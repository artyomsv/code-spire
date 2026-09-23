package dev.codespire.runtime.docker;

import dev.codespire.runtime.SignInRuntime;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A name conflict proves another worker holds the unit; failing to look it up must not turn that into
 * a failure that ends the owner's sign-in (review of PR #168).
 */
class DockerSignInClaimTest {

    private static final SignInRuntime.Handle OWNER = new SignInRuntime.Handle("TEST-sign-in", "TEST-container");

    @Test
    void aLookupThatFailsStillMeansHeld() {
        SignInRuntime.AlreadyClaimed claimed = DockerSignInRuntime.claimedBy("TEST-sign-in",
                () -> { throw new IllegalStateException("TEST daemon hiccup"); }, handle -> true);

        assertEquals("TEST-sign-in", claimed.existing().unitId());
        assertFalse(claimed.existingIsRunning(), "nothing known about it, so not claimed to be running");
    }

    /** The owner removed its container between the conflict and the lookup. */
    @Test
    void aHolderThatJustVanishedStillMeansHeld() {
        SignInRuntime.AlreadyClaimed claimed = DockerSignInRuntime.claimedBy("TEST-sign-in", Optional::empty, handle -> true);

        assertEquals("TEST-sign-in", claimed.existing().unitId());
    }

    @Test
    void aHolderThatIsFoundIsNamedWithItsState() {
        SignInRuntime.AlreadyClaimed claimed = DockerSignInRuntime.claimedBy("TEST-sign-in", () -> Optional.of(OWNER), handle -> true);

        assertEquals("TEST-container", claimed.existing().reference());
        assertTrue(claimed.existingIsRunning());
    }
}
