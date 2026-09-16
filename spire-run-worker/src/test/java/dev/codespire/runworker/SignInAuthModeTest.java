package dev.codespire.runworker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the worker reads out of a sign-in file, and what it refuses to (M3.5 part F).
 *
 * <p>The API-key shape below is measured ({@code @openai/codex@0.146.0}, 2026-09-16). The subscription
 * shape is NOT: no real sign-in has been made yet, so these cases assert only that a mode which is not
 * the API-key one is read back unchanged — never that it has a particular name.
 */
class SignInAuthModeTest {

    /** The measured API-key file, with an obviously fake key. */
    private static final String API_KEY_FILE = "{\"auth_mode\":\"apikey\",\"OPENAI_API_KEY\":\"sk-TEST-not-real\"}";

    @Test
    void theMeasuredApiKeyFileReportsItsMode() {
        assertEquals(SignInAuthMode.API_KEY_MODE, SignInAuthMode.of(API_KEY_FILE));
    }

    /** Any other mode is carried through untouched. The name is the vendor's and has not been seen. */
    @Test
    void anUnmeasuredModeIsReadBackRatherThanInterpreted() {
        assertEquals("TEST-some-other-mode",
                SignInAuthMode.of("{\"auth_mode\":\"TEST-some-other-mode\",\"tokens\":{\"access\":\"x\"}}"));
    }

    /** A file that does not say is null, so the caller refuses rather than storing an unknown kind. */
    @Test
    void aFileThatDoesNotSayItsModeAnswersNothing() {
        assertNull(SignInAuthMode.of("{\"tokens\":{\"access\":\"x\"}}"));
        assertNull(SignInAuthMode.of(""));
    }

    /** Nothing but the mode leaves this class. The key in the measured file must not become an identity. */
    @Test
    void theIdentityIsTheModeAndNeverAnythingElseInTheFile() {
        assertEquals(SignInAuthMode.API_KEY_MODE, SignInAuthMode.identity(API_KEY_FILE));
        assertFalse(SignInAuthMode.identity(API_KEY_FILE).contains("sk-"),
                "a screen label must not carry any part of the credential");
    }

    @Test
    void aFileWithNoModeStillYieldsASafeLabel() {
        assertEquals("unknown", SignInAuthMode.identity("{\"tokens\":{}}"));
    }

    /**
     * An address can never become a screen label, because it can never become a mode.
     *
     * <p>An address is the obvious thing a vendor would store as "who is this", and it is the one value
     * this project never persists or logs. The protection is the mode pattern itself — letters, digits,
     * underscore and hyphen — so this asserts the property rather than a separate check, which could
     * only ever have been unreachable code sitting behind it.
     */
    @Test
    void aModeShapedLikeAnAddressYieldsNoIdentityAtAll() {
        String addressShaped = "{\"auth_mode\":\"TEST@example.test\"}";

        assertNull(SignInAuthMode.of(addressShaped));
        assertEquals("unknown", SignInAuthMode.identity(addressShaped));
        assertFalse(SignInAuthMode.identity(addressShaped).contains("@"));
    }
}
