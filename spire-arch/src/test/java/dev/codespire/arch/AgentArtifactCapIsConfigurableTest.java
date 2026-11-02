package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference agent entrypoint must take its artifact cap from the run, not hard-code it.
 *
 * <p>A review run's answer may be up to 256 KiB ({@code RunReviewArtifact.MAX_TEXT_BYTES}). The
 * entrypoint once fixed {@code ARTIFACT_MAX_BYTES=32768}, so a review answer above 32 KiB was cut
 * inside the agent container before the publisher's larger cap ever saw it: a paid run whose answer
 * nobody could read. The run worker now passes {@code SPIRE_ARTIFACT_MAX_BYTES} on a review run's
 * agent container, and the script must read it.
 *
 * <p>Read as text, like every other guard here; the script is already a declared input of this task.
 */
class AgentArtifactCapIsConfigurableTest {

    private static final String ENTRYPOINT = "deploy/agent/spire-agent-entrypoint.sh";

    /** A bare assignment of the old constant, at the start of a line. */
    private static final Pattern HARD_CODED = Pattern.compile("(?m)^\\s*ARTIFACT_MAX_BYTES=32768\\s*$");

    @Test
    void theEntrypointReadsTheArtifactCapFromItsEnvironment() throws IOException {
        String script = RootBuild.read(ENTRYPOINT);

        assertTrue(script.contains("SPIRE_ARTIFACT_MAX_BYTES"),
                ENTRYPOINT + " does not read SPIRE_ARTIFACT_MAX_BYTES, so a review run's 256 KiB "
                        + "answer is cut at the image's own cap");
        assertFalse(HARD_CODED.matcher(script).find(),
                ENTRYPOINT + " still hard-codes ARTIFACT_MAX_BYTES=32768");
    }
}
