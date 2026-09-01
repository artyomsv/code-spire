package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.HarnessInvocation;
import dev.codespire.harness.PromptDelivery;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunUnitSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a command into the three-container run unit of ADR-038.
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
 *   <li>the init container gets a READ-only clone credential, so the token that can write never
 *       enters the container that touches agent-reachable disk first.</li>
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

    private static final String PUBLISHER_IMAGE = "spire-publisher:latest";

    /** An agent can write an object bomb; the publisher refuses one larger than this. */
    private static final long BUNDLE_MAX_BYTES = 256L * 1024 * 1024;

    private static final long MEMORY_BYTES = 4L * 1024 * 1024 * 1024;

    private static final long NANO_CPUS = 2_000_000_000L;

    @Inject
    Credentials credentials;

    public RunUnitSpec build(RunCommand.ExecuteRun command, HarnessAdapter adapter) {
        Credentials.Scm scm = credentials.scm(command.runId(), command.scmCredential());
        Map<String, String> harnessEnv = credentials.harnessEnv(command.runId(), command.harnessCredential());

        ContainerSpec init = new ContainerSpec(
                PUBLISHER_IMAGE,
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
                PUBLISHER_IMAGE,
                List.of("spire-publish"),
                Map.of("SPIRE_REMOTE_URI", command.remoteUri(),
                        "SPIRE_BRANCH", command.branch(),
                        "SPIRE_BRANCH_BASE", command.baseBranch(),
                        "SPIRE_BASE_COMMIT", command.baseCommit(),
                        "SPIRE_PROTECTED_PATHS", String.join(",", command.protectedPaths()),
                        "SPIRE_BUNDLE_MAX_BYTES", Long.toString(BUNDLE_MAX_BYTES),
                        "SPIRE_GIT_USERNAME", scm.writeUsername(),
                        "SPIRE_GIT_SECRET", scm.writeSecret()),
                // Read-only, and the ONLY volume it sees. Not the workspace.
                List.of(Mount.readOnly(HANDOFF, "/handoff")));

        return new RunUnitSpec(command.runId(), init, agent, publisher,
                MEMORY_BYTES, NANO_CPUS, Duration.ofSeconds(command.maxWallClockSeconds()));
    }
}
