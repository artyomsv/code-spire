package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.HarnessInvocation;
import dev.codespire.harness.PromptDelivery;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.Mount;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The init and agent containers of a run unit, as {@link RunUnitBuilder} assembles them. */
final class RunUnitContainers {

    private RunUnitContainers() {
    }

    /** The init container: it clones with the read credential, from a checkpoint when the run continues one. */
    static ContainerSpec init(RunCommand.ExecuteRun command, Credentials.Scm scm, String publisherImage) {
        Map<String, String> initEnv = new LinkedHashMap<>();
        initEnv.put("SPIRE_REMOTE_URI", command.remoteUri());
        initEnv.put("SPIRE_BRANCH", command.branch());
        initEnv.put("SPIRE_BASE_COMMIT", command.baseCommit());
        initEnv.put("SPIRE_CLONE_USERNAME", scm.readUsername());
        initEnv.put("SPIRE_CLONE_SECRET", scm.readSecret());
        List<Mount> initMounts = new ArrayList<>(List.of(Mount.writable(RunUnitBuilder.WORKSPACE, "/workspace")));
        if (command.startFromRunId() != null) {
            // A retried build continues from the previous held build's checkpoint (M4): the trusted init
            // reads that run's bundles read-only and resets this run's branch to the gated head.
            initEnv.put("SPIRE_CHECKPOINT_HEAD", command.startFromHead());
            initEnv.put("SPIRE_CHECKPOINT_DIR", RunUnitBuilder.CHECKPOINT_PATH);
            initEnv.put("SPIRE_BUNDLE_MAX_BYTES", Long.toString(RunUnitBuilder.BUNDLE_MAX_BYTES));
            initMounts.add(Mount.ofRun(command.startFromRunId(), RunUnitBuilder.HANDOFF,
                    RunUnitBuilder.CHECKPOINT_PATH));
        }
        return new ContainerSpec(publisherImage, List.of("spire-clone"), Map.copyOf(initEnv), initMounts);
    }

    /**
     * The agent: the harness's command and environment for {@code invocation}, and the workspace and handoff.
     *
     * <p>A STDIN arm gets its prompt in {@code SPIRE_PROMPT}: the agent image's entrypoint contract
     * (deploy/agent/spire-agent-entrypoint.sh) writes it to a file OUTSIDE the working tree, unsets it, and pipes it to
     * the harness on stdin. Nothing in the runtime touches stdin, so without it a STDIN arm reads an empty prompt,
     * produces nothing, exits 0, and the run is reported as finished having done nothing, which is exactly what the
     * harness SPI's own javadoc warns about. It is never on argv (see thePromptIsNeverInTheAgentsArgv). It is the
     * invocation's prompt, not the command's: the topology's instruction is appended once, and a STDIN arm reading the
     * raw one would be the only delivery that never asks for a commit, the difference invisible until a branch came
     * back saying "work in progress".
     */
    static ContainerSpec agent(RunCommand.ExecuteRun command, HarnessAdapter adapter, HarnessInvocation invocation) {
        Map<String, String> agentEnv = new LinkedHashMap<>(adapter.environment(invocation));
        agentEnv.put("SPIRE_BASE_COMMIT", command.baseCommit());
        agentEnv.put("SPIRE_HANDOFF", "/handoff");
        if (adapter.promptDelivery() == PromptDelivery.STDIN) {
            agentEnv.put("SPIRE_PROMPT", invocation.prompt());
        }
        return new ContainerSpec(
                command.agentImage(),
                adapter.command(invocation),
                Map.copyOf(agentEnv),
                List.of(Mount.writable(RunUnitBuilder.WORKSPACE, "/workspace"),
                        Mount.writable(RunUnitBuilder.HANDOFF, "/handoff")));
    }
}
