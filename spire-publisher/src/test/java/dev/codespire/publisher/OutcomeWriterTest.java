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
     * A SHORT git secret is scrubbed, which is the regression this closes.
     *
     * <p>This test asserted the exact opposite one round ago, and the reversal is worth reading
     * rather than skipping. The shared class briefly skipped anything under eight characters, on the
     * reasoning that redacting a short string makes a failure detail unreadable. True — and the wrong
     * trade at the point of USE, because Gitea and Forgejo accept an account password for
     * git-over-HTTP with a default minimum of six, and this container holds the git WRITE token. A
     * real password reached {@code factory_run.failure_detail} verbatim.
     *
     * <p><b>Nothing here now separates the shared class from a hypothetical local copy, and that is
     * the correct state rather than a gap.</b> With the floor gone the two agree on every path this
     * class can reach: it holds exactly one credential, so ordering is unreachable, and the three
     * forms were already common to both. Where the difference IS reachable it is covered in
     * {@code SecretScrubTest}. What guarantees the publisher reaches the shared class is structural —
     * its own copy is deleted and it has no other scrub — not an assertion.
     */
    @Test
    void aShortGitSecretIsStillScrubbed() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutcomeWriter writer = new OutcomeWriter(new PrintStream(captured, true, StandardCharsets.UTF_8),
                "bot", "sh0rt1");

        writer.failed("PUSH_REJECTED", "remote rejected https://bot:sh0rt1@host/x");

        String line = captured.toString(StandardCharsets.UTF_8);
        assertFalse(line.contains("sh0rt1"),
                "a six-character forge password is what Gitea issues, and this process holds the git "
                        + "WRITE token: " + line);
        assertTrue(line.contains(SecretScrub.REDACTED), line);
    }
}
