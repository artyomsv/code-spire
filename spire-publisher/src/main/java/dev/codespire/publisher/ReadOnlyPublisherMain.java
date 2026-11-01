package dev.codespire.publisher;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * Distinct executable, like {@link HeldPublisherMain}: an older publisher image has no {@code spire-publish-none}, so
 * a read-only unit on it fails to start rather than falling back to a pushing publisher.
 *
 * <p>It reads {@code SPIRE_HANDOFF_DIR} and the artifact cap {@code SPIRE_ARTIFACT_MAX_BYTES}, and nothing else. No
 * remote, branch or credential is read, so a unit that hands it none is not misconfigured, and one that hands it a
 * credential by mistake still cannot push.
 */
public final class ReadOnlyPublisherMain {

    /** Where the run unit mounts the handoff volume, as for the other publisher commands. */
    private static final String DEFAULT_HANDOFF_DIR = "/handoff";

    private static final long POLL_MILLIS = 1_000;

    private ReadOnlyPublisherMain() {
    }

    public static void main(String[] args) {
        int exitCode = run(System.getenv(), System.out);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(Map<String, String> env, PrintStream out) {
        OutcomeWriter outcome = new OutcomeWriter(out);
        Path handoff = Path.of(env.getOrDefault("SPIRE_HANDOFF_DIR", DEFAULT_HANDOFF_DIR));
        try {
            // Read inside the guard: a malformed cap is a named publisher failure, never a silent 32 KiB.
            int artifactMaxBytes = HandoffFiles.artifactMaxBytes(env);
            return new ReadOnlyPublisher(handoff, outcome, POLL_MILLIS).artifactMaxBytes(artifactMaxBytes).run();
        } catch (IOException | RuntimeException e) {
            outcome.failed("PUBLISHER_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome.failed("PUBLISHER_FAILED", "interrupted while waiting for DONE");
            return 1;
        }
    }
}
