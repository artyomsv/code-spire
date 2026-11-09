package dev.codespire.runtime.docker;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunUnitSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.codespire.runtime.docker.ModelProxyUnits.MODEL_SECRET;
import static dev.codespire.runtime.docker.ModelProxyUnits.PROXIED_REPORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model proxy container on a real daemon: where it sits in the unit's networks, how it is sandboxed, and that it
 * goes with the unit. What crosses it is {@link DockerModelProxyTrafficIT}.
 */
class DockerModelProxyIT {

    private final DockerUnits units = new DockerUnits();

    private final DockerRunRuntime runtime = units.runtime();

    private final ModelProxyUnits proxied = new ModelProxyUnits(units);

    @BeforeAll
    static void buildTheImages() throws Exception {
        ModelProxyUnits.buildTheImages();
    }

    @AfterEach
    void tearDownEveryUnit() {
        units.tearDown();
    }

    private RunHandle startReporting(String runId, String agentScript) {
        return units.start(proxied.proxiedUnit(runId, agentScript,
                ModelProxyUnits.Vendor.reporting(proxied.startUpstream(200))));
    }

    @Test
    void theModelProxyJoinsTheAgentAndModelNetworksOnly() {
        String runId = "run_model_networks";
        startReporting(runId, "true");

        Map<String, ContainerNetwork> networks = units.networksOf(runId, ModelProxyContainer.ROLE);
        String agentNetwork = DockerRunNetworks.agentNetworkName(runId);
        assertEquals(Set.of(agentNetwork, DockerRunNetworks.modelNetworkName(runId)), networks.keySet(),
                "the model proxy must be on the agent's network and the model network, never on the default bridge");
        assertTrue(networks.get(agentNetwork).getAliases().contains(RunUnitSpec.MODEL_PROXY_ALIAS),
                "the agent finds the model proxy by its alias: " + networks.get(agentNetwork).getAliases());
        assertEquals(Set.of(agentNetwork), units.networksOf(runId, "agent").keySet(),
                "the agent stays on its own network alone");
    }

    @Test
    void theEgressProxyIsNotOnTheAgentNetwork() {
        String runId = "run_model_egress_net";
        startReporting(runId, "true");

        assertEquals(Set.of("bridge", DockerRunNetworks.modelNetworkName(runId)),
                units.networksOf(runId, DockerRunNetworks.PROXY).keySet(),
                "with a model proxy, only the model proxy may reach the egress proxy");
    }

    @Test
    void theModelProxyIsSandboxedAndItsSecretIsNoLabel() {
        String runId = "run_model_sandbox";
        startReporting(runId, "true");

        var inspected = runtime.client().inspectContainerCmd(proxied.modelProxyOf(runId).getId()).exec();
        HostConfig host = inspected.getHostConfig();
        assertEquals(ModelProxyContainer.MEMORY_BYTES, host.getMemory());
        assertEquals(ModelProxyContainer.NANO_CPUS, host.getNanoCPUs());
        assertEquals(ModelProxyContainer.PIDS_LIMIT, host.getPidsLimit());
        assertEquals(Boolean.TRUE, host.getReadonlyRootfs());
        assertTrue(List.of(host.getCapDrop()).contains(Capability.ALL));
        assertTrue(host.getSecurityOpts().contains("no-new-privileges"));
        assertTheLogIsRotated(host.getLogConfig());
        assertFalse(inspected.getConfig().getLabels().values().stream().anyMatch(value -> value.contains(MODEL_SECRET)),
                "a credential never goes in a label: " + inspected.getConfig().getLabels().keySet());
    }

    /** One line per request, so a long run's log is rotated: at most three files of 2 MiB, under the parser's bound. */
    private static void assertTheLogIsRotated(LogConfig logConfig) {
        String inspectedLog = "the model proxy's log config as inspected: " + logConfig;
        // By name: the inspected enum constant was measured in CI as a different instance of json-file, so identity
        // fails.
        assertEquals("json-file", logConfig.getType() == null ? null : logConfig.getType().getType(), inspectedLog);
        assertEquals("2m", logConfig.getConfig() == null ? null : logConfig.getConfig().get("max-size"), inspectedLog);
        assertEquals("3", logConfig.getConfig() == null ? null : logConfig.getConfig().get("max-file"), inspectedLog);
    }

    /**
     * A model proxy that refuses its route exits at once; the run's failure detail says so with its state and the
     * refusal line, which names the variable and never a value (live acceptance of harness reviews step 3).
     */
    @Test
    void aModelProxyThatRefusesItsRouteIsReportedWithItsStateAndRefusal() {
        String runId = "run_model_refused_route";
        ContainerSpec invalid = new ContainerSpec(DockerUnits.MODEL_PROXY_IMAGE, List.of(), Map.of(
                "SPIRE_MODEL_UPSTREAM", "http://127.0.0.1",
                "SPIRE_MODEL_INJECTION", "BEARER",
                "SPIRE_MODEL_PATHS_EXACT", "v1/responses",
                "SPIRE_MODEL_SECRET", MODEL_SECRET,
                "SPIRE_MODEL_ALLOW_PLAINTEXT_UPSTREAM", "true"), List.of());
        units.started().add(new RunHandle(runId, "unknown"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runtime.create(units.egressUnit(runId, "true", PROXIED_REPORT).withModelProxy(invalid)));

        String detail = failure.getMessage();
        assertTrue(detail.contains("the model proxy"), detail);
        assertTrue(detail.contains("state exited"), detail);
        assertTrue(detail.contains("SPIRE_MODEL_PATHS_EXACT holds an entry that does not start with /"), detail);
        assertFalse(detail.contains(MODEL_SECRET), "a failure detail never carries the credential");
    }

    @Test
    void destroyRemovesTheModelProxyAndBothNetworks() {
        String runId = "run_model_destroy";
        RunHandle handle = startReporting(runId, "true");
        runtime.salvage(handle);
        assertEquals(2, units.labelledNetworks(runId).size(),
                "the unit must have both networks: " + units.labelledNetworks(runId));

        new DockerRunRuntime().destroy(handle);

        assertTrue(units.labelledNetworks(runId).isEmpty(), "both networks must go with the unit");
        assertTrue(runtime.client().listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(DockerRunRuntime.RUN_ID_LABEL, runId)).exec().isEmpty(),
                "and every container, the model proxy included");
    }

    @Test
    void cancelStopsTheModelProxy() {
        String runId = "run_model_cancel";
        RunHandle handle = startReporting(runId, "sleep 300");

        runtime.cancel(handle);

        assertEquals(Boolean.FALSE, runtime.client().inspectContainerCmd(proxied.modelProxyOf(runId).getId()).exec()
                .getState().getRunning(), "the model proxy holds the credential and must stop with the run");
    }
}
