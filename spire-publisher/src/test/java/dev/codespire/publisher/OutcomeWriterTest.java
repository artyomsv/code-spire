package dev.codespire.publisher;

import dev.codespire.secrets.SecretScrub;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutcomeWriterTest {

    private static final String SECRET = "TEST-secret-77b1";

    /** DockerRunRuntime.MAX_LINE_CHARS: the longest outcome line the run worker receives whole. */
    private static final int MAX_LINE_CHARS = 64 * 1024;

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

    /** The artifact and summary are model output, and a model can echo whatever reached its environment. */
    @Test
    void anArtifactLineIsRedacted() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutcomeWriter writer = new OutcomeWriter(new PrintStream(captured, true, StandardCharsets.UTF_8),
                "bot", "TEST-token");

        writer.artifact("x TEST-token y");
        writer.stepSummary("{\"decisions\":[\"TEST-token\"]}");

        String lines = captured.toString(StandardCharsets.UTF_8);
        assertTrue(lines.contains("\"event\":\"artifact\""), lines);
        assertTrue(lines.contains("\"event\":\"step_summary\""), lines);
        assertFalse(lines.contains("TEST-token"), lines);
    }

    /**
     * The run worker receives a line longer than 64 Ki characters clipped, and skips a clipped line it cannot parse, so
     * an artifact that grew past that bound in JSON escaping vanished as if the agent wrote nothing. Jackson escapes
     * each C0 control character as six characters, so 20 KiB of tool output carrying colour codes was enough.
     */
    @Test
    void aControlCharacterDenseArtifactUnderTheFileCapStillFitsOneLine() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutcomeWriter writer = new OutcomeWriter(new PrintStream(captured, true, StandardCharsets.UTF_8));
        String text = "\u001B".repeat(10_000) + "TEST-artifact\n".repeat(700);

        writer.artifact(text);

        String[] lines = captured.toString(StandardCharsets.UTF_8).split("\n");
        assertEquals(1, lines.length);
        assertTrue(lines[0].length() <= MAX_LINE_CHARS, "line of " + lines[0].length() + " characters");
        assertTrue(lines[0].startsWith("{\"event\":\"artifact\""), lines[0].substring(0, 40));
        assertTrue(lines[0].contains("TEST-artifact\\nTEST-artifact"), "the text survives without its controls");
        assertFalse(lines[0].toLowerCase(Locale.ROOT).contains("\\u001b"), "control characters are dropped");
    }

    /**
     * Text that escapes past one line without any control character goes out in parts (harness reviews, step 4). It was
     * refused as TOO_LARGE before parts existed, which lost an artifact inside its file cap to JSON escaping alone.
     */
    @Test
    void anArtifactThatEscapesPastOneLineGoesOutInParts() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutcomeWriter writer = new OutcomeWriter(new PrintStream(captured, true, StandardCharsets.UTF_8));
        String text = "\"".repeat(HandoffFiles.ARTIFACT_MAX_BYTES);

        writer.artifact(text);

        String output = captured.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("handoff_file_refused"), output.substring(0, 200));
        assertEquals(text, ArtifactParts.join(output));
    }

    /**
     * The part size is chosen against the worst escaping JSON does: six characters for one control character. The
     * writer strips most of them before a part is cut, so this drives the part line directly with a part made of
     * nothing else, at the largest size the partition makes.
     */
    @Test
    void aPartOfOnlyControlCharactersStillFitsOneLine() {
        java.util.List<String> parts = ArtifactPartition.split("\u0001".repeat(3 * ArtifactPartition.MAX_PART_BYTES));

        assertEquals(3, parts.size());
        for (String part : parts) {
            String line = OutcomeWriter.partLine(999, 1000, part);
            assertTrue(line.length() <= MAX_LINE_CHARS, "a part line of " + line.length() + " characters");
        }
    }

    /** Parts are cut on code points: a four-byte character is never split across two parts. */
    @Test
    void aPartNeverSplitsACharacter() {
        String text = "a" + "\uD83D\uDE00".repeat(ArtifactPartition.MAX_PART_BYTES);

        java.util.List<String> parts = ArtifactPartition.split(text);

        assertEquals(text, String.join("", parts));
        for (String part : parts) {
            assertTrue(part.getBytes(StandardCharsets.UTF_8).length <= ArtifactPartition.MAX_PART_BYTES);
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)), "a part ends mid-character");
        }
    }

    /**
     * An artifact too long for one line travels as ordered parts (harness reviews, step 4): a 200 KiB review answer is
     * cut into numbered parts, each line under the run worker's line cap, and joined back it is the text as written.
     */
    @Test
    void aLongArtifactIsWrittenAsOrderedParts() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutcomeWriter writer = new OutcomeWriter(new PrintStream(captured, true, StandardCharsets.UTF_8));
        String text = "TEST-finding \"quoted\" ü\n".repeat(9_000);

        writer.artifact(text);

        String output = captured.toString(StandardCharsets.UTF_8);
        assertEquals(text, ArtifactParts.join(output));
        assertFalse(output.contains("handoff_file_refused"), output.substring(0, Math.min(200, output.length())));
    }

    @Test
    void theHandoffLinesCarryTheirFields() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutcomeWriter writer = new OutcomeWriter(new PrintStream(captured, true, StandardCharsets.UTF_8));

        writer.repositoryChanged(2);
        writer.handoffFileRefused("artifact.md", "SYMLINK");

        String lines = captured.toString(StandardCharsets.UTF_8);
        assertTrue(lines.contains("{\"event\":\"repository_changed\",\"bundles\":2}"), lines);
        assertTrue(lines.contains(
                "{\"event\":\"handoff_file_refused\",\"file\":\"artifact.md\",\"why\":\"SYMLINK\"}"), lines);
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
