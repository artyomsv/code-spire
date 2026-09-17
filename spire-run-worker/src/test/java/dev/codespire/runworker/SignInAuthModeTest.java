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

    /**
     * A NESTED value is not the top-level field, and reading it as one decided a credential's kind.
     *
     * <p>The first version matched a regular expression over the whole document, so this file — whose
     * real top-level mode is {@code apikey} — was classified as {@code other} and would have been
     * stored as a subscription. That member is billed as an asserted zero while the vendor charges for
     * every token, and both look like a pool row afterwards.
     */
    @Test
    void aNestedModeDoesNotDecideTheFilesKind() {
        assertEquals(SignInAuthMode.API_KEY_MODE,
                SignInAuthMode.of("{\"metadata\":{\"auth_mode\":\"TEST-other\"},\"auth_mode\":\"apikey\"}"));
    }

    /** Text that merely contains the words is not a sign-in file. */
    @Test
    void somethingThatIsNotJsonAnswersNothing() {
        assertNull(SignInAuthMode.of("this is not json but it says auth_mode: apikey"));
        assertNull(SignInAuthMode.of("[{\"auth_mode\":\"apikey\"}]"), "the top level must be an object");
    }

    /** JSON allows a repeated key and readers disagree which wins, so a repeat is no answer at all. */
    @Test
    void aModeDeclaredTwiceIsRefusedRatherThanResolved() {
        assertNull(SignInAuthMode.of("{\"auth_mode\":\"apikey\",\"auth_mode\":\"TEST-other\"}"));
    }

    /** A mode that is not a string is not a mode. */
    @Test
    void aNonStringModeAnswersNothing() {
        assertNull(SignInAuthMode.of("{\"auth_mode\":{\"nested\":true}}"));
        assertNull(SignInAuthMode.of("{\"auth_mode\":7}"));
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
     * this project never persists or logs. The protection is the mode's own character bound — letters,
     * digits, underscore and hyphen — which the earlier regular-expression reader provided by accident
     * and the JSON reader has to state on purpose.
     */
    @Test
    void aModeShapedLikeAnAddressYieldsNoIdentityAtAll() {
        String addressShaped = "{\"auth_mode\":\"TEST@example.test\"}";

        assertNull(SignInAuthMode.of(addressShaped));
        assertEquals("unknown", SignInAuthMode.identity(addressShaped));
        assertFalse(SignInAuthMode.identity(addressShaped).contains("@"));
    }

    /** And anything longer than a token is not one either. */
    @Test
    void aModeThatIsAWholeSentenceIsNotAMode() {
        assertNull(SignInAuthMode.of("{\"auth_mode\":\"" + "x".repeat(33) + "\"}"));
        assertNull(SignInAuthMode.of("{\"auth_mode\":\"some words here\"}"));
    }
}
