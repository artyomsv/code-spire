package dev.codespire.runworker;

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Driven with a secret containing characters that must be percent-encoded, on purpose: a token of
 * plain letters and digits encodes to itself, so a test using one passes whether or not the encoded
 * form is handled at all.
 */
class SecretScrubTest {

    private static final String USERNAME = "TEST-machine-account";

    /** The slash and plus are what make the encoded form differ from the literal. */
    private static final String SECRET = "TEST-tok/en+with=specials";

    @Test
    void theLiteralSecretIsRemoved() {
        String cleaned = SecretScrub.of(USERNAME, SECRET).clean("git failed for " + SECRET + " at origin");

        assertFalse(cleaned.contains(SECRET));
        assertTrue(cleaned.contains(SecretScrub.REDACTED));
        assertTrue(cleaned.startsWith("git failed for "), "the surrounding text must survive");
    }

    @Test
    void theUrlEncodedFormIsRemoved() {
        // A credential inside a URL is percent-encoded, so the literal match never sees it.
        String encoded = URLEncoder.encode(SECRET, StandardCharsets.UTF_8);
        assertFalse(encoded.equals(SECRET), "the fixture must actually differ once encoded");

        String cleaned = SecretScrub.of(USERNAME, SECRET).clean("https://x:" + encoded + "@forge/repo.git");

        assertFalse(cleaned.contains(encoded));
    }

    @Test
    void theBasicAuthPairIsRemoved() {
        // Basic authentication sends user:secret base64-encoded, so an exception quoting a request
        // header leaks a credential that neither of the forms above matches.
        String basic = Base64.getEncoder().encodeToString(
                (USERNAME + ":" + SECRET).getBytes(StandardCharsets.UTF_8));

        String cleaned = SecretScrub.of(USERNAME, SECRET).clean("Authorization: Basic " + basic);

        assertFalse(cleaned.contains(basic));
    }

    @Test
    void aLongerSecretContainingAShorterOneIsFullyRemoved() {
        // Redacting the shorter first would leave the tail of the longer one in the text. Ordering
        // is the fix, and this is the case that proves the ordering is applied.
        String shorter = "TEST-abcdefgh";
        String longer = shorter + "-and-more";

        String cleaned = SecretScrub.of(null, shorter, longer).clean("saw " + longer + " here");

        assertFalse(cleaned.contains(shorter), "no fragment of either secret may survive");
        assertFalse(cleaned.contains("-and-more"));
    }

    @Test
    void aScrubWithNothingToRemoveLeavesTheTextAlone() {
        // The path where no credential could be decrypted. Returning the text unchanged is the only
        // option available -- there is nothing to match -- and it must not corrupt the detail.
        String detail = "IllegalStateException: the daemon went away";

        assertEquals(detail, SecretScrub.none().clean(detail));
        assertEquals(detail, SecretScrub.of(USERNAME).clean(detail));
    }

    @Test
    void aShortValueIsNotTreatedAsASecret() {
        // A very short "secret" matches ordinary words and would redact the message into noise,
        // hiding the failure it was meant to describe.
        String cleaned = SecretScrub.of(null, "abc").clean("abc appears in this ordinary sentence");

        assertEquals("abc appears in this ordinary sentence", cleaned);
    }

    @Test
    void nullTextSurvives() {
        assertEquals(null, SecretScrub.of(USERNAME, SECRET).clean(null));
    }
}
