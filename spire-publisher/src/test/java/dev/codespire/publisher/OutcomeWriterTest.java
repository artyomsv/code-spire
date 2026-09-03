package dev.codespire.publisher;

import dev.codespire.secrets.SecretScrub;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutcomeWriterTest {

    private static final String SECRET = "TEST-secret-77b1";

    @Test
    void theCredentialNeverReachesAFailureLine() {
        // Failure details quote exception messages, and a transport exception quotes the URL it
        // tried. Refusing userinfo in the URI closes the front door; this closes the one behind it.
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutcomeWriter writer = new OutcomeWriter(new PrintStream(captured, true, StandardCharsets.UTF_8), SECRET);

        writer.failed("PUSH_FAILED", "TransportException: https://bot:" + SECRET + "@host/x: rejected");

        String line = captured.toString(StandardCharsets.UTF_8);
        assertFalse(line.contains(SECRET), line);
        assertTrue(line.contains("\"cause\":\"PUSH_FAILED\""), line);
        assertTrue(line.contains("https://bot:" + SecretScrub.REDACTED + "@host/x"), line);
    }

    @Test
    void theCredentialIsScrubbedInTheFormsATransportErrorRendersIt() {
        // JGit speaks HTTP Basic: a message can carry the token percent-encoded inside a URI, or
        // Base64-encoded as the Authorization value together with the username. Neither contains
        // the literal token, so the literal-only scrub let both through.
        String secret = "TEST/secret+77b1";
        String encoded = java.net.URLEncoder.encode(secret, StandardCharsets.UTF_8);
        String basic = java.util.Base64.getEncoder()
                .encodeToString(("bot:" + secret).getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutcomeWriter writer = new OutcomeWriter(
                new PrintStream(captured, true, StandardCharsets.UTF_8), "bot", secret);
        writer.failed("PUSH_FAILED", "https://bot:" + encoded + "@host/x rejected; Authorization: Basic " + basic);
        String line = captured.toString(StandardCharsets.UTF_8);
        assertFalse(line.contains(encoded), line);
        assertFalse(line.contains(basic), line);
        assertTrue(line.contains("https://bot:" + SecretScrub.REDACTED + "@host/x"), line);
        assertTrue(line.contains("Basic " + SecretScrub.REDACTED), line);
    }

    @Test
    void aWriterWithNoSecretWritesTheDetailAsIs() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutcomeWriter writer = new OutcomeWriter(new PrintStream(captured, true, StandardCharsets.UTF_8));

        writer.failed("BUNDLE_UNREADABLE", "EmptyBundle: nothing to fetch");

        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("nothing to fetch"));
    }

    /**
     * Below the shared floor nothing is redacted, and that is BOTH the decision and the proof.
     *
     * <p><b>The decision.</b> The publisher's own copy had no length floor, so this is the single
     * behaviour it loses by moving to the shared class. Pinned here so the next reader meets a
     * decision rather than a surprise: redacting a short string turns every innocent occurrence of
     * it into the marker and leaves an operator a failure detail they cannot read. No forge issues a
     * token this short, and where a caller CAN refuse one outright it does — see
     * {@code EnterpriseEnvironmentConfig}, which fails startup on a proxy password below the floor.
     *
     * <p><b>The proof.</b> It is also the one assertion here that separates the shared class from a
     * local copy. Every other test in this file supplies one long secret and asserts the marker,
     * which the deleted private method satisfied just as well. This one does not: that method would
     * have redacted {@code short}, because it had no floor to stop it.
     *
     * <p>Ordering — longest form first, so one secret containing another is fully redacted — is a
     * real difference between the two copies and is NOT asserted here, because it cannot be reached
     * through this class: {@link OutcomeWriter} holds exactly one credential. It is covered where it
     * is reachable, in {@code SecretScrubTest}. An earlier version of this test claimed to assert it
     * and did not.
     */
    @Test
    void aSecretBelowTheSharedFloorIsNotScrubbed() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutcomeWriter writer = new OutcomeWriter(new PrintStream(captured, true, StandardCharsets.UTF_8),
                "bot", "short");

        writer.failed("PUSH_REJECTED", "remote rejected https://bot:short@host/x");

        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("short"),
                "a secret below SecretScrub.MIN_SECRET_LENGTH is deliberately left alone");
    }
}
