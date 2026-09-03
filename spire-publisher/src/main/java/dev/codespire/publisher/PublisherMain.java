package dev.codespire.publisher;

import dev.codespire.workspace.PublishRepo;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The publisher sidecar.
 *
 * <p><b>This is the only part of a run unit holding a git write credential.</b> Its safety rests on
 * four properties, each asserted by a test somewhere in this repository rather than by this comment:
 *
 * <ul>
 *   <li>it never mounts or reads {@code /workspace} — asserted against a real daemon in
 *       {@code DockerRunRuntimeIT.thePublisherCannotSeeTheAgentsWorkspace};</li>
 *   <li>{@code /handoff} is read-only to it — {@code DockerRunRuntimeIT.handoffIsReadOnlyToThePublisher},
 *       which is the test that caught the read-only mount not being read-only at all;</li>
 *   <li>it works only in its own clone, made by {@link PublishRepo} from the remote;</li>
 *   <li>it never checks out a working tree, so nothing the agent authored becomes a file here.</li>
 * </ul>
 *
 * <p>Configuration is environment-only and validated once, before the clone — see
 * {@link PublisherConfig} for what is refused and why.
 */
public final class PublisherMain {

    /** The agent's last act. Only needed where the runtime has no native sidecar termination. */
    static final String DONE_SENTINEL = "DONE";

    private static final long POLL_MILLIS = 1_000;

    private PublisherMain() {
    }

    public static void main(String[] args) throws Exception {
        // Before the config, so a corporate deployment gets the same transport the init clone
        // got. The push is the other git call that meets the proxy, and it is the one whose
        // failure costs an entire agent run.
        CorporateTransport.apply(System.getenv());
        PublisherConfig config;
        try {
            config = PublisherConfig.fromEnv(System.getenv());
        } catch (IllegalStateException e) {
            // A refusal names the variable, never its value.
            new OutcomeWriter().failed("PUBLISHER_MISCONFIGURED", e.getMessage());
            System.exit(2);
            return;
        }
        OutcomeWriter outcome = new OutcomeWriter(System.out, config.credential().username(), config.credential().secret());
        int exitCode = 0;

        try (PublishRepo repo = PublishRepo.cloneBranch(config.remoteUri(), config.baseBranch(),
                Files.createTempDirectory("spire-publish-clone-"), config.credential())) {

            PublishCycle cycle = new PublishCycle(repo, config.baseCommit(), config.branch(),
                    config.protectedPaths(), config.bundleMaxBytes(), config.credential(), outcome);

            HandoffWatcher watcher = new HandoffWatcher(config.handoffDir());
            boolean carryOn = true;
            while (carryOn && agentStillRunning(config.handoffDir())) {
                carryOn = drain(watcher, cycle);
                if (carryOn) {
                    Thread.sleep(POLL_MILLIS);
                }
            }
            if (carryOn) {
                // The agent is gone; take whatever it wrote in its last moments before exiting.
                drain(watcher, cycle);
            }
        } catch (IOException | GitAPIException | InterruptedException | RuntimeException e) {
            // The process boundary: every failure that reaches here is reported on stdout as the
            // run worker expects, then the sidecar exits non-zero. Each source is named -- the clone,
            // the temp directory, the poll sleep, the watcher, and anything the cycle raises --
            // rather than caught as Exception, so a new checked type cannot slip in unannounced.
            outcome.failed("PUBLISHER_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
            exitCode = 1;
        } finally {
            // Runs on BOTH paths. System.exit inside the catch skipped this block, so a failed
            // publisher held its pack windows open on the way out.
            PublishRepo.releaseAllPackWindows();
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static boolean drain(HandoffWatcher watcher, PublishCycle cycle) throws IOException {
        boolean[] carryOn = {true};
        watcher.poll(bundle -> {
            if (carryOn[0]) {
                carryOn[0] = cycle.handle(bundle);
            }
        });
        return carryOn[0];
    }

    /**
     * Whether the agent is still working.
     *
     * <p>The one platform-dependent part. Where the runtime declares {@code nativeSidecar},
     * Kubernetes terminates this container when the agent exits and the loop simply ends. Where it
     * does not — Docker — the agent's last act is to write the sentinel, and this watches for it.
     */
    private static boolean agentStillRunning(Path handoff) {
        return !Files.exists(handoff.resolve(DONE_SENTINEL));
    }
}
