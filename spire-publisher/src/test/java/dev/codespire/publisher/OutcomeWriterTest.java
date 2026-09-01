package dev.codespire.publisher;

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
        assertTrue(line.contains("https://bot:***@host/x"), line);
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
        assertTrue(line.contains("https://bot:***@host/x"), line);
        assertTrue(line.contains("Basic ***"), line);
    }

    @Test
    void aWriterWithNoSecretWritesTheDetailAsIs() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutcomeWriter writer = new OutcomeWriter(new PrintStream(captured, true, StandardCharsets.UTF_8));

        writer.failed("BUNDLE_UNREADABLE", "EmptyBundle: nothing to fetch");

        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("nothing to fetch"));
    }
}
