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
    void aWriterWithNoSecretWritesTheDetailAsIs() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutcomeWriter writer = new OutcomeWriter(new PrintStream(captured, true, StandardCharsets.UTF_8));

        writer.failed("BUNDLE_UNREADABLE", "EmptyBundle: nothing to fetch");

        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("nothing to fetch"));
    }
}
