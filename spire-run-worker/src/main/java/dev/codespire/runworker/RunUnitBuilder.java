package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.HarnessInvocation;
import dev.codespire.harness.PromptDelivery;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunUnitSpec;
import dev.codespire.runtime.VerifyUnitSpec;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.ArrayList;
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

    /**
     * Whether a unit's agent reaches the network only through its egress proxy, and whether that proxy refuses. The
     * hosts it may reach come from the harness, never from the command: a repository or a ticket cannot widen them.
     */
    @ConfigProperty(name = "spire.run.egress.mode")
    EgressPolicy.Mode egressMode;

    /** An agent can write an object bomb; the publisher refuses one larger than this. */
    static final long BUNDLE_MAX_BYTES = 256L * 1024 * 1024;

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
        return build(command, adapter, Topology.CONTINUOUS);
    }

    /**
     * A specification or plan run's unit (M4 slice 3): init and agent as {@link #build}, and a publisher that runs
     * {@code spire-publish-none} with the handoff and no write credential, so it cannot push even if its code were wrong.
     */
    public RunUnitSpec buildReadOnly(RunCommand.ExecuteWorkDraftRun draft, HarnessAdapter adapter) {
        RunUnitSpec unit = build(draft.execution(), adapter, Topology.READ_ONLY);
        return unit.withPublisher(PublisherEnvironments.readOnly(unit.publisher()));
    }

    /**
     * A harness review run's unit (harness reviews, step 4): the read-only topology, with the review answer's artifact
     * cap handed to the publisher.
     */
    public RunUnitSpec buildReview(RunCommand.ExecuteReviewRun review, HarnessAdapter adapter) {
        throw new UnsupportedOperationException("review units are not built yet: " + review.runId());
    }

    /** Where a retried build's init reads the previous run's handoff. */
    static final String CHECKPOINT_PATH = "/checkpoint";

    /**
     * The verify unit for one attempt against a held build (M4, spec §3.1): a prepare container in the publisher
     * image with the build's own read credential, and one container per check in the build's agent image with
     * no credential and only the new verify volume mounted.
     */
    public VerifyUnitSpec verifyUnit(RunCommand.ExecuteWorkRun held, RunCommand.VerifyWork command) {
        if (!held.runId().equals(command.runId()) || !held.work().equals(command.work())) {
            throw new IllegalArgumentException("A verify must name the held build it checks");
        }
        if (command.timeoutSeconds() > maxWallClockSeconds) {
            throw new IllegalArgumentException("the verify time limit of " + command.timeoutSeconds()
                    + "s is over this worker's spire.run.max-wall-clock-seconds (" + maxWallClockSeconds + ")");
        }
        RunCommand.ExecuteRun execution = held.execution();
        Credentials.Scm scm = credentials.scm(execution.runId(), execution.scmCredential());
        String volume = VerifyUnitSpec.VOLUME_PREFIX + command.attemptId();
        Map<String, String> prepareEnv = new LinkedHashMap<>();
        prepareEnv.put("SPIRE_REMOTE_URI", execution.remoteUri());
        prepareEnv.put("SPIRE_BASE_COMMIT", execution.baseCommit());
        prepareEnv.put("SPIRE_CLONE_USERNAME", scm.readUsername());
        prepareEnv.put("SPIRE_CLONE_SECRET", scm.readSecret());
        prepareEnv.put("SPIRE_CHECKPOINT_HEAD", command.head());
        prepareEnv.put("SPIRE_HANDOFF_DIR", "/handoff");
        prepareEnv.put("SPIRE_WORKSPACE_DIR", VerifyUnitSpec.WORKSPACE_PATH);
        prepareEnv.put("SPIRE_BUNDLE_MAX_BYTES", Long.toString(BUNDLE_MAX_BYTES));
        ContainerSpec prepare = new ContainerSpec(publisherImage, List.of("spire-verify-prepare"), Map.copyOf(prepareEnv),
                List.of(Mount.readOnly(HANDOFF, "/handoff"), Mount.writable(volume, VerifyUnitSpec.WORKSPACE_PATH)));
        List<ContainerSpec> checks = command.commands().stream()
                .map(line -> new ContainerSpec(execution.agentImage(), List.of(line), Map.of(),
                        List.of(Mount.writable(volume, VerifyUnitSpec.WORKSPACE_PATH)), List.of("/bin/sh", "-c")))
                .toList();
        return new VerifyUnitSpec(command.runId(), command.attemptId(), prepare, checks, enterprise.environment(),
                MEMORY_BYTES, NANO_CPUS, DISK_BYTES, Duration.ofSeconds(command.timeoutSeconds()));
    }

    public RunUnitSpec buildHeld(RunCommand.ExecuteWorkRun command, HarnessAdapter adapter) {
        RunUnitSpec unit = build(command.execution(), adapter, Topology.HELD);
        ContainerSpec publisher = PublisherEnvironments.held(unit.publisher(), command);
        if (!command.execution().pushesToAnExistingBranch())
            return unit.withPublisher(publisher);
        // A land fix (M4 slice 7a) builds on the item's own branch and may need to merge the pull request's base.
        // The trusted init keeps that base under refs/spire-base, because removing origin drops every
        // remote-tracking ref. The agent still receives no remote and no credential.
        Map<String, String> initEnv = new LinkedHashMap<>(unit.init().environment());
        initEnv.put("SPIRE_FETCH_BRANCH", command.execution().protectedBranch());
        ContainerSpec init = new ContainerSpec(unit.init().image(), unit.init().argv(), initEnv, unit.init().mounts());
        return unit.withInit(init).withPublisher(publisher);
    }

    /** Which publisher a unit runs, and so which instruction its agent's prompt ends with. */
    private enum Topology {
        CONTINUOUS("spire-publish"), HELD("spire-publish-held"), READ_ONLY(PublisherEnvironments.READ_ONLY_COMMAND);

        private final String publisherCommand;

        Topology(String publisherCommand) {
            this.publisherCommand = publisherCommand;
        }

        String prompt(String dispatched) {
            return this == READ_ONLY ? AgentPrompts.withReadOnlyInstruction(dispatched)
                    : AgentPrompts.withCommitInstruction(dispatched);
        }
    }

    private RunUnitSpec build(RunCommand.ExecuteRun command, HarnessAdapter adapter, Topology topology) {
        if (command.maxWallClockSeconds() > maxWallClockSeconds) {
            throw new IllegalArgumentException("the command asks for a wall clock of " + command.maxWallClockSeconds()
                    + "s, over this worker's spire.run.max-wall-clock-seconds (" + maxWallClockSeconds
                    + "); the channel's ack budget is sized to the latter");
        }
        Credentials.Scm scm = credentials.scm(command.runId(), command.scmCredential());
        Map<String, String> harnessEnv = credentials.harnessEnv(command.runId(), command.harnessCredential(),
                command.harnessSignIn());

        Map<String, String> initEnv = new LinkedHashMap<>();
        initEnv.put("SPIRE_REMOTE_URI", command.remoteUri());
        initEnv.put("SPIRE_BRANCH", command.branch());
        initEnv.put("SPIRE_BASE_COMMIT", command.baseCommit());
        initEnv.put("SPIRE_CLONE_USERNAME", scm.readUsername());
        initEnv.put("SPIRE_CLONE_SECRET", scm.readSecret());
        List<Mount> initMounts = new ArrayList<>(List.of(Mount.writable(WORKSPACE, "/workspace")));
        if (command.startFromRunId() != null) {
            // A retried build continues from the previous held build's checkpoint (M4): the trusted init
            // reads that run's bundles read-only and resets this run's branch to the gated head.
            initEnv.put("SPIRE_CHECKPOINT_HEAD", command.startFromHead());
            initEnv.put("SPIRE_CHECKPOINT_DIR", CHECKPOINT_PATH);
            initEnv.put("SPIRE_BUNDLE_MAX_BYTES", Long.toString(BUNDLE_MAX_BYTES));
            initMounts.add(Mount.ofRun(command.startFromRunId(), HANDOFF, CHECKPOINT_PATH));
        }
        ContainerSpec init = new ContainerSpec(publisherImage, List.of("spire-clone"), Map.copyOf(initEnv), initMounts);

        HarnessInvocation invocation = new HarnessInvocation(command.runId(),
                topology.prompt(command.prompt()),
                "/workspace", command.model(), harnessEnv,
                Duration.ofSeconds(command.maxWallClockSeconds()), command.reasoningEffort());

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
            // The invocation's prompt, not the command's: the topology's instruction is appended once,
            // and a STDIN arm reading the raw one would be the only delivery that never asks for a
            // commit — the difference invisible until a branch came back saying "work in progress".
            agentEnv.put("SPIRE_PROMPT", invocation.prompt());
        }
        ContainerSpec agent = new ContainerSpec(
                command.agentImage(),
                adapter.command(invocation),
                Map.copyOf(agentEnv),
                List.of(Mount.writable(WORKSPACE, "/workspace"),
                        Mount.writable(HANDOFF, "/handoff")));

        ContainerSpec publisher = new ContainerSpec(
                publisherImage,
                List.of(topology.publisherCommand),
                PublisherEnvironments.publisherEnvironment(command, scm),
                // Read-only, and the ONLY volume it sees. Not the workspace.
                List.of(Mount.readOnly(HANDOFF, "/handoff")));

        EgressPolicy egress = egressMode == EgressPolicy.Mode.OFF ? EgressPolicy.OFF
                : new EgressPolicy(egressMode, adapter.egressHosts(invocation));
        return new RunUnitSpec(command.runId(), init, agent, publisher,
                enterprise.environment(), egress,
                MEMORY_BYTES, NANO_CPUS, DISK_BYTES, Duration.ofSeconds(command.maxWallClockSeconds()));
    }

    /** Reuse the retained unit's topology; decrypt only the current SCM credential, never a harness key. */
    public RunUnitSpec publication(RunUnitSpec held, RunCommand.ExecuteWorkRun original,
                                    RunCommand.PublishWorkRun request) {
        if (!held.runId().equals(request.runId()) || !original.runId().equals(request.runId())
                || !original.work().equals(request.permit().work())) {
            throw new IllegalArgumentException("Publication does not match the retained build");
        }
        Credentials.Scm scm = credentials.scm(request.runId(), request.scmCredential());
        Map<String, String> env = new HashMap<>(PublisherEnvironments.publisherEnvironment(original.execution(), scm));
        // Preserve the original floor too: a policy edit cannot weaken a run's initial path bounds.
        var paths = new java.util.LinkedHashSet<>(original.execution().protectedPaths());
        paths.addAll(request.permit().protectedPaths());
        env.put("SPIRE_PROTECTED_PATHS", String.join(",", paths));
        env.put("SPIRE_PERMITTED_HEAD", request.permit().head());
        env.put("SPIRE_PERMIT_ISSUED_AT", request.permit().issuedAt().toString());
        env.putAll(PublisherEnvironments.existingBranchEnvironment(original, request.permit()));
        env.put("SPIRE_PERMIT_EXPIRES_AT", request.permit().expiresAt().toString());
        ContainerSpec publisher = new ContainerSpec(held.publisher().image(), List.of("spire-publish-permitted"),
                env, held.publisher().mounts());
        return held.withPublisher(publisher);
    }
}
