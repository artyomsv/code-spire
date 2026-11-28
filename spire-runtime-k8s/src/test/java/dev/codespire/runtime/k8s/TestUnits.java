package dev.codespire.runtime.k8s;

import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.HostMount;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunUnitSpec;
import dev.codespire.runtime.UnitInputs;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Run units for tests. Placeholders only: TEST names, :TEST image tags, no real credential. */
final class TestUnits {

    static final String INPUTS_VOLUME = "inputs";
    static final String INPUTS_PATH = "/inputs";

    private TestUnits() {
    }

    /** A review unit the Kubernetes arm accepts. */
    static RunUnitSpec proxiedReview(String runId) {
        return build(runId, EnterpriseEnvironment.NONE, EgressPolicy.Mode.ENFORCE, true,
                defaultInputs(), Map.of(), Set.of("github.com"), null);
    }

    static RunUnitSpec withoutModelProxy(String runId) {
        return build(runId, EnterpriseEnvironment.NONE, EgressPolicy.Mode.ENFORCE, false,
                defaultInputs(), Map.of(), Set.of("github.com"), null);
    }

    /** Egress off, which a model proxy cannot be built with, so there is none either. */
    static RunUnitSpec egressOff(String runId) {
        return build(runId, EnterpriseEnvironment.NONE, EgressPolicy.Mode.OFF, false,
                defaultInputs(), Map.of(), Set.of("github.com"), null);
    }

    static RunUnitSpec withHostMount(String runId) {
        EnterpriseEnvironment enterprise = new EnterpriseEnvironment(
                List.of(new HostMount("/etc/TEST-ca.pem", "/etc/ssl/TEST-ca.pem")), Map.of());
        return build(runId, enterprise, EgressPolicy.Mode.ENFORCE, true,
                defaultInputs(), Map.of(), Set.of("github.com"), null);
    }

    static RunUnitSpec withForeignMount(String runId) {
        return build(runId, EnterpriseEnvironment.NONE, EgressPolicy.Mode.ENFORCE, true,
                defaultInputs(), Map.of(), Set.of("github.com"),
                Mount.ofRun("TEST-other-run", "checkpoint", "/checkpoint"));
    }

    static RunUnitSpec withoutInputs(String runId) {
        return build(runId, EnterpriseEnvironment.NONE, EgressPolicy.Mode.ENFORCE, true,
                null, Map.of(), Set.of("github.com"), null);
    }

    /** Inputs of exactly {@code bytes} UTF-8 bytes (key plus value). */
    static RunUnitSpec withInputsOfBytes(String runId, int bytes) {
        String name = "big.md";
        UnitInputs inputs = new UnitInputs(INPUTS_VOLUME, INPUTS_PATH,
                Map.of(name, "x".repeat(bytes - name.length())));
        return build(runId, EnterpriseEnvironment.NONE, EgressPolicy.Mode.ENFORCE, true,
                inputs, Map.of(), Set.of("github.com"), null);
    }

    static RunUnitSpec largeInputs(String runId) {
        return withInputsOfBytes(runId, (int) UnitRefusals.MAX_SECRET_BYTES + 1);
    }

    static RunUnitSpec inputsOfTwoHundredTwentyFourKibibytes(String runId) {
        return withInputsOfBytes(runId, 224 * 1024);
    }

    /** The agent's environment is exactly {@code bytes} UTF-8 bytes (key plus value). */
    static RunUnitSpec withAgentEnvironmentOfBytes(String runId, int bytes) {
        String name = "TEST_BIG";
        return build(runId, EnterpriseEnvironment.NONE, EgressPolicy.Mode.ENFORCE, true,
                defaultInputs(), Map.of(name, "x".repeat(bytes - name.length())), Set.of("github.com"), null);
    }

    static RunUnitSpec largeEnvironment(String runId) {
        return withAgentEnvironmentOfBytes(runId, (int) UnitRefusals.MAX_SECRET_BYTES + 1);
    }

    static RunUnitSpec withoutFetchHost(String runId) {
        return proxiedReview(runId).withFetchHosts(Set.of());
    }

    private static UnitInputs defaultInputs() {
        return new UnitInputs(INPUTS_VOLUME, INPUTS_PATH, Map.of("context.md", "TEST context"));
    }

    private static RunUnitSpec build(String runId, EnterpriseEnvironment enterprise, EgressPolicy.Mode mode,
                                     boolean modelProxy, UnitInputs inputs, Map<String, String> agentEnvironment,
                                     Set<String> fetchHosts, Mount extraAgentMount) {
        List<Mount> initMounts = new ArrayList<>();
        initMounts.add(Mount.writable("work", "/work"));
        List<Mount> agentMounts = new ArrayList<>();
        agentMounts.add(Mount.readOnly("work", "/work"));
        if (inputs != null) {
            initMounts.add(Mount.writable(INPUTS_VOLUME, INPUTS_PATH));
            agentMounts.add(Mount.readOnly(INPUTS_VOLUME, INPUTS_PATH));
        }
        if (extraAgentMount != null) {
            agentMounts.add(extraAgentMount);
        }
        ContainerSpec init = new ContainerSpec("TEST-init:TEST", List.of("init"), Map.of(), initMounts);
        ContainerSpec agent = new ContainerSpec("TEST-agent:TEST", List.of("review"), agentEnvironment, agentMounts);
        ContainerSpec publisher = new ContainerSpec("TEST-publisher:TEST", List.of("publish"), Map.of(),
                List.of(Mount.writable("out", "/out")));
        EgressPolicy egress = mode == EgressPolicy.Mode.OFF
                ? EgressPolicy.OFF
                : new EgressPolicy(mode, Set.of("api.test-vendor.example"));
        RunUnitSpec spec = new RunUnitSpec(runId, init, agent, publisher, enterprise, egress,
                1L << 30, 1_000_000_000L, 1L << 30, Duration.ofMinutes(10));
        if (modelProxy) {
            spec = spec.withModelProxy(new ContainerSpec("TEST-model-proxy:TEST", List.of(), Map.of(), List.of()));
        }
        if (inputs != null) {
            spec = spec.withInputs(inputs);
        }
        return spec.withFetchHosts(fetchHosts);
    }
}
