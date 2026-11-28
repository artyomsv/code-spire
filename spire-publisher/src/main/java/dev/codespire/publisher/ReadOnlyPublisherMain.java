package dev.codespire.publisher;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

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

    static final String STAY_UNTIL_TERM = "--stay-until-term";

    private static final int BAD_ARGUMENTS = 2;

    public static void main(String[] args) {
        if (args.length == 1 && STAY_UNTIL_TERM.equals(args[0])) {
            stay();
            return;
        }
        if (args.length > 0) {
            System.err.println("spire-publish-none: unknown argument " + args[0]);
            System.exit(BAD_ARGUMENTS);
        }
        int exitCode = run(System.getenv(), System.out);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * A shutdown hook counts the latch down and waits for the main thread; the main thread finishes its last poll and
     * print, then ends the JVM with {@code halt}, since {@code System.exit} would block inside a shutdown already
     * under way.
     */
    private static void stay() {
        CountDownLatch term = new CountDownLatch(1);
        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            term.countDown();
            try {
                main.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        int exitCode = run(System.getenv(), System.out, term);
        System.out.flush();
        Runtime.getRuntime().halt(exitCode);
    }

    static int run(Map<String, String> env, PrintStream out) {
        return run(env, out, null);
    }

    /** @param term null for the Docker mode (return at DONE); a latch for stay mode */
    static int run(Map<String, String> env, PrintStream out, CountDownLatch term) {
        OutcomeWriter outcome = new OutcomeWriter(out);
        Path handoff = Path.of(env.getOrDefault("SPIRE_HANDOFF_DIR", DEFAULT_HANDOFF_DIR));
        try {
            // Read inside the guard: a malformed cap is a named publisher failure, never a silent 32 KiB.
            int artifactMaxBytes = HandoffFiles.artifactMaxBytes(env);
            ReadOnlyPublisher publisher = new ReadOnlyPublisher(handoff, outcome, POLL_MILLIS)
                    .artifactMaxBytes(artifactMaxBytes);
            if (term != null) {
                publisher.stayUntilTerm(term);
            }
            return publisher.run();
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
