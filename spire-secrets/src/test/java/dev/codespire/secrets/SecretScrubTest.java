package dev.codespire.secrets;

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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

    /**
     * A short secret DOES redact ordinary text, and that cost is accepted deliberately.
     *
     * <p>This test asserted the opposite until a review found what the opposite costs. A short
     * "secret" does match ordinary words and does turn a message into noise — but it is still a
     * credential, and the alternative was leaving a real six-character forge password in a column an
     * operator reads. Noise is recoverable; a leaked write token is not.
     *
     * <p>Kept as a test rather than deleted, because the cost is real and someone will meet it and
     * wonder. The answer is in the WARN {@code of} logs and in
     * {@link SecretScrub#MIN_PLAUSIBLE_SECRET_LENGTH}: use a longer credential.
     */
    @Test
    void aShortSecretIsScrubbedEvenThoughItMakesTheMessageNoisy() {
        String cleaned = SecretScrub.of(List.of(new SecretScrub.Credential(null, "abc")))
                .clean("abc appears in this ordinary sentence");

        assertEquals(SecretScrub.REDACTED + " appears in this ordinary sentence", cleaned);
    }

    @Test
    void nullTextSurvives() {
        assertEquals(null, SecretScrub.of(USERNAME, SECRET).clean(null));
    }

    /**
     * A SHORT secret is scrubbed, because at the moment of use it is still a credential.
     *
     * <p><b>This reverses a decision, and the reversal is the point.</b> A length floor once
     * skipped anything under eight characters, on the reasoning that a short "secret" is more
     * likely a common substring and redacting it makes a failure detail unreadable. The reasoning
     * about readability holds. The trade does not: it spends a security property to buy a
     * readability one, at the one instant where the value IS a live credential and the cost of
     * leaking it is unbounded.
     *
     * <p>It is not hypothetical either. Gitea and Forgejo accept an ACCOUNT PASSWORD for
     * git-over-HTTP with a default minimum of six characters, and nothing validates the length of
     * a factory token an operator pastes in. Such a password reached
     * {@code factory_run.failure_detail} verbatim, which an operator reads.
     *
     * <p>The readability concern is answered where a human can act on it — see
     * {@link SecretScrub#MIN_PLAUSIBLE_SECRET_LENGTH}, which is now a warning about a configured
     * value rather than a silent decision about a live one.
     */
    @Test
    void aSecretShorterThanThePlausibleMinimumIsStillScrubbed() {
        String shortButReal = "abc123";

        String cleaned = SecretScrub.of(List.of(new SecretScrub.Credential("bot", shortButReal)))
                .clean("remote rejected https://bot:" + shortButReal + "@host/x");

        assertFalse(cleaned.contains(shortButReal),
                "a six-character forge password is what Gitea issues, and it is still a credential: "
                        + cleaned);
        assertTrue(cleaned.contains(SecretScrub.REDACTED), cleaned);
    }

    /** A blank or null secret has no form to redact, and redacting "" would erase everything. */
    @Test
    void aBlankSecretContributesNoForm() {
        String text = "nothing to hide here";

        assertEquals(text, SecretScrub.of(List.of(new SecretScrub.Credential("bot", ""))).clean(text));
        assertEquals(text, SecretScrub.of(List.of(new SecretScrub.Credential("bot", null))).clean(text));
        assertEquals(text, SecretScrub.of(List.of(new SecretScrub.Credential("bot", "   "))).clean(text));
    }

    /**
     * The warning fires, and carries the LENGTH and neither the value nor the username.
     *
     * <p>This is the whole compensation for removing the length gate — "a warning an operator can
     * read beats a silence they cannot" — and it was asserted by nothing. In a class whose entire
     * purpose is keeping credentials out of logs, the single most damaging edit possible is adding
     * the secret to this line's arguments, and it would have passed every test in the repository.
     */
    @Test
    void aShortSecretWarnsWithItsLengthAndNeitherItsValueNorItsUsername() {
        List<String> warnings = warningsFrom(
                () -> SecretScrub.of(List.of(new SecretScrub.Credential("bot", "abc123"))));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("{0}") || warnings.getFirst().contains("6"),
                "the length is what makes the warning actionable: " + warnings.getFirst());
        assertFalse(warnings.getFirst().contains("abc123"), "never the value");
        assertFalse(warnings.getFirst().contains("bot"), "never the username either");
    }

    /** The negative control: a plausible credential is silent, or the warning is just noise. */
    @Test
    void aPlausibleSecretWarnsAboutNothing() {
        assertTrue(warningsFrom(
                () -> SecretScrub.of(List.of(new SecretScrub.Credential("bot", SECRET)))).isEmpty());
    }

    /**
     * Captures what this class logs. It logs through {@code System.Logger}, which routes to
     * {@code java.util.logging} in a module that depends on the JDK and nothing else.
     *
     * <p>Copied from {@code UnifiedDiffParserTest} in spire-diff, the other framework-free module
     * that had to solve this.
     */
    private static List<String> warningsFrom(Runnable building) {
        Logger logger = Logger.getLogger(SecretScrub.class.getName());
        List<String> captured = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord entry) {
                captured.add(entry.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            building.run();
        } finally {
            logger.removeHandler(handler);
        }
        return captured;
    }
}
