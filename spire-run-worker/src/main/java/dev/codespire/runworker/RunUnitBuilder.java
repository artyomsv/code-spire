package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.HarnessInvocation;
import dev.codespire.harness.PromptDelivery;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunUnitSpec;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a command into the three-container run unit of ADR-039.
 *
 * <p><b>The security properties of the whole design are decided here, by what each container is
 * handed.</b> Nothing downstream can restore a property this method gives away:
 *
 * <ul>
 *   <li>the agent gets the model credential and the workspace, and NO write credential — so it
 *       physically cannot push, gate or no gate;</li>
 *   <li>the publisher gets the write credential and the gate's rules, and NOT the workspace — so it
 *       can never reach agent-authored git config or hooks;</li>
 *   <li>{@code /handoff} is writable by the agent and read-only to the publisher;</li>
 *   <li>the init container gets the clone credential — read-only in the design, and today the
 *       machine account's single secret, which can also write. The agent is on the far side of
 *       that either way: it receives no git credential at all. See {@code docs/UNVERIFIED.md} §E.</li>
 * </ul>
 *
 * <p>Each is asserted by a test, because each is a boundary rather than a preference. Two of them
 * are additionally enforced by {@link RunUnitSpec}'s own constructor, which refuses a publisher that
 * can write to anything the agent can write to — a second line of defence added after review found
 * that the typed read-only flag was being set correctly and read by nobody.
 */
@ApplicationScoped
public class RunUnitBuilder {

    static final String WORKSPACE = "workspace";

    static final String HANDOFF = "handoff";

    /**
     * Not a constant: a registry prefix and a digest are the deployment's to choose, and a
     * {@code latest} tag cannot say which publisher gated a run. The agent image is configured the
     * same way on the orchestrator (FactoryConfig); these are the two halves of one run unit.
     */
    @ConfigProperty(name = "spire.run.publisher-image")
    String publisherImage;

    /**
     * The longest wall clock a command may carry. RunAckBudget sizes the channel's ack threshold
     * to this number; a command asking for more would outlive the budget the guard promised, so it
     * is refused as BAD_COMMAND before a container exists rather than honoured.
     */
    @ConfigProperty(name = "spire.run.max-wall-clock-seconds")
    long maxWallClockSeconds;

    /** An agent can write an object bomb; the publisher refuses one larger than this. */
    private static final long BUNDLE_MAX_BYTES = 256L * 1024 * 1024;

    private static final long MEMORY_BYTES = 4L * 1024 * 1024 * 1024;

    /**
     * The disk budget a run unit declares. <b>How much of it an arm can actually spend differs.</b>
     *
     * <p>On <b>Kubernetes</b> it covers the whole unit: {@code emptyDir} is a POD volume, so it
     * survives an init container exiting, and {@code medium: Memory} with {@code sizeLimit} bounds
     * the shared workspace as well as {@code /tmp}.
     *
     * <p>On <b>Docker</b> it buys the AGENT's {@code /tmp} and nothing else. The shared volumes are
     * not bounded there — a tmpfs local volume is dropped when the last container using it stops, so
     * a tmpfs {@code /workspace} would wipe the clone between init exiting and the agent starting —
     * and the init and publisher containers are deliberately left alone, because the publisher clones
     * into {@code java.io.tmpdir} and a bound {@code /tmp} there fails a large repository AFTER the
     * model has been paid. See {@code DockerRunRuntime.tmpFsFor}; the residual is
     * {@code techdebt/spire-runtime-docker/2-3-…} and RUN-TOPOLOGY §9.7.
     *
     * <p><b>An earlier version of this comment said the opposite of all of it</b> — that the shared
     * volumes were bounded on every arm, and that a large clone would ENOSPC rather than fill the
     * daemon's disk. On the only shipped arm the clone goes to the UNBOUNDED {@code /workspace}, so
     * it will do exactly what the sentence claimed was fixed. This is the file where the number is
     * chosen, which makes it the file a future author reads.
     *
     * <p>Sized against {@link #MEMORY_BYTES} because a tmpfs is charged to the container's memory
     * cgroup: 2 GiB of scratch inside a 4 GiB container leaves the agent process the other half. Note
     * the limit is PER CONTAINER, so a unit's worst case is not 4 GiB but three times it.
     */
    private static final long DISK_BYTES = 2L * 1024 * 1024 * 1024;

    private static final long NANO_CPUS = 2_000_000_000L;

    @Inject
    Credentials credentials;

    /**
     * The deployment's corporate CA bundle and proxy, applied to all three containers.
     *
     * <p>Read here rather than folded into each ContainerSpec below, so that "every container
     * of the unit" is a property of {@link RunUnitSpec} and not of this method. The init
     * container is the one most easily forgotten and the most damaging to forget: without the
     * bundle its clone fails at the forge, and a clone failure reads like a bad credential.
     */
    @Inject
    EnterpriseEnvironmentConfig enterprise;

    public RunUnitSpec build(RunCommand.ExecuteRun command, HarnessAdapter adapter) {
        if (command.maxWallClockSeconds() > maxWallClockSeconds) {
            throw new IllegalArgumentException("the command asks for a wall clock of " + command.maxWallClockSeconds()
                    + "s, over this worker's spire.run.max-wall-clock-seconds (" + maxWallClockSeconds
                    + "); the channel's ack budget is sized to the latter");
        }
        Credentials.Scm scm = credentials.scm(command.runId(), command.scmCredential());
        Map<String, String> harnessEnv = credentials.harnessEnv(command.runId(), command.harnessCredential());

        ContainerSpec init = new ContainerSpec(
                publisherImage,
                List.of("spire-clone"),
                Map.of("SPIRE_REMOTE_URI", command.remoteUri(),
                        "SPIRE_BRANCH", command.branch(),
                        "SPIRE_BASE_COMMIT", command.baseCommit(),
                        "SPIRE_CLONE_USERNAME", scm.readUsername(),
                        "SPIRE_CLONE_SECRET", scm.readSecret()),
                List.of(Mount.writable(WORKSPACE, "/workspace")));

        HarnessInvocation invocation = new HarnessInvocation(command.runId(), command.prompt(),
                "/workspace", command.model(), harnessEnv,
                Duration.ofSeconds(command.maxWallClockSeconds()));

        Map<String, String> agentEnv = new LinkedHashMap<>(adapter.environment(invocation));
        agentEnv.put("SPIRE_BASE_COMMIT", command.baseCommit());
        agentEnv.put("SPIRE_HANDOFF", "/handoff");
        if (adapter.promptDelivery() == PromptDelivery.STDIN) {
            // The agent image's entrypoint contract (deploy/agent/spire-agent-entrypoint.sh): the
            // prompt arrives in SPIRE_PROMPT, is written to a file OUTSIDE the working tree, unset,
            // and piped to the harness on stdin. Nothing in the runtime touches stdin, so without
            // this line a STDIN arm reads an empty prompt, produces nothing, exits 0 -- and the run
            // is reported as finished having done nothing, which is exactly what the harness SPI's
            // own javadoc warns about. It is never on argv (see thePromptIsNeverInTheAgentsArgv).
            agentEnv.put("SPIRE_PROMPT", command.prompt());
        }
        ContainerSpec agent = new ContainerSpec(
                command.agentImage(),
                adapter.command(invocation),
                Map.copyOf(agentEnv),
                List.of(Mount.writable(WORKSPACE, "/workspace"),
                        Mount.writable(HANDOFF, "/handoff")));

        ContainerSpec publisher = new ContainerSpec(
                publisherImage,
                List.of("spire-publish"),
                publisherEnvironment(command, scm),
                // Read-only, and the ONLY volume it sees. Not the workspace.
                List.of(Mount.readOnly(HANDOFF, "/handoff")));

        return new RunUnitSpec(command.runId(), init, agent, publisher,
                enterprise.environment(),
                MEMORY_BYTES, NANO_CPUS, DISK_BYTES, Duration.ofSeconds(command.maxWallClockSeconds()));
    }

    /**
     * What the publisher is told, including where ADR-040 lets it push.
     *
     * <p>A {@code HashMap} rather than {@code Map.of}, because the two branch-mode variables are
     * CONDITIONAL and {@code Map.of} cannot express that. Writing them unconditionally would be
     * worse than verbose: {@code SPIRE_PROTECTED_BRANCH} set to an empty string reads to the
     * publisher as "present but blank", which its own required-in-existing-mode check treats
     * exactly as absent — so the distinction would survive here and be lost there.
     *
     * <p>The mode is written only when it is {@code existing}. An absent variable is the M0 rule,
     * which is the safe direction and the one every run before ADR-040 used.
     */
    private static Map<String, String> publisherEnvironment(RunCommand.ExecuteRun command,
                                                            Credentials.Scm scm) {
        Map<String, String> env = new HashMap<>();
        env.put("SPIRE_REMOTE_URI", command.remoteUri());
        env.put("SPIRE_BRANCH", command.branch());
        env.put("SPIRE_BRANCH_BASE", command.baseBranch());
        env.put("SPIRE_BASE_COMMIT", command.baseCommit());
        env.put("SPIRE_PROTECTED_PATHS", String.join(",", command.protectedPaths()));
        env.put("SPIRE_BUNDLE_MAX_BYTES", Long.toString(BUNDLE_MAX_BYTES));
        env.put("SPIRE_GIT_USERNAME", scm.writeUsername());
        env.put("SPIRE_GIT_SECRET", scm.writeSecret());
        if (command.pushesToAnExistingBranch()) {
            env.put("SPIRE_BRANCH_MODE", "existing");
            env.put("SPIRE_PROTECTED_BRANCH", command.protectedBranch());
        }
        return Map.copyOf(env);
    }
}
