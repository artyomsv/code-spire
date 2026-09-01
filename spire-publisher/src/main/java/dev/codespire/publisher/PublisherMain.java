package dev.codespire.publisher;

import dev.codespire.workspace.GitCredential;
import dev.codespire.workspace.PublishRepo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

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
 * <p>Configuration is environment-only, with no defaults for anything an operator must decide. A
 * missing value is a startup failure naming the variable, because a publisher that starts with the
 * wrong branch or an absent credential fails later, in the middle of a run, having already consumed
 * the agent's work.
 */
public final class PublisherMain {

    private static final String HANDOFF = "/handoff";

    /** The agent's last act. Only needed where the runtime has no native sidecar termination. */
    static final String DONE_SENTINEL = "DONE";

    private static final long POLL_MILLIS = 1_000;

    private PublisherMain() {
    }

    public static void main(String[] args) throws Exception {
        OutcomeWriter outcome = new OutcomeWriter();
        Path handoff = Path.of(System.getenv().getOrDefault("SPIRE_HANDOFF_DIR", HANDOFF));

        try (PublishRepo repo = PublishRepo.cloneBranch(
                required("SPIRE_REMOTE_URI"),
                required("SPIRE_BRANCH_BASE"),
                Files.createTempDirectory("spire-publish-clone-"),
                credential())) {

            PublishCycle cycle = new PublishCycle(repo,
                    required("SPIRE_BASE_COMMIT"),
                    required("SPIRE_BRANCH"),
                    profileGlobs(),
                    Long.parseLong(required("SPIRE_BUNDLE_MAX_BYTES")),
                    credential(),
                    outcome);

            HandoffWatcher watcher = new HandoffWatcher(handoff);
            boolean carryOn = true;
            while (carryOn && agentStillRunning(handoff)) {
                carryOn = drain(watcher, cycle);
                if (carryOn) {
                    Thread.sleep(POLL_MILLIS);
                }
            }
            if (carryOn) {
                // The agent is gone; take whatever it wrote in its last moments before exiting.
                drain(watcher, cycle);
            }
        } catch (Exception e) {
            outcome.failed("PUBLISHER_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        } finally {
            PublishRepo.releaseAllPackWindows();
        }
    }

    private static boolean drain(HandoffWatcher watcher, PublishCycle cycle) throws Exception {
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

    private static GitCredential credential() {
        return new GitCredential(required("SPIRE_GIT_USERNAME"), required("SPIRE_GIT_SECRET"));
    }

    private static List<String> profileGlobs() {
        String raw = System.getenv("SPIRE_PROTECTED_PATHS");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * No defaults, on purpose. A publisher that silently starts with the wrong branch, or with no
     * credential, discovers it in the middle of a run — after the agent's work is already done.
     */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required and was not set");
        }
        return value;
    }
}
