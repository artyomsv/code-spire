package dev.codespire.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fetch hosts and the fetch placement of a run unit (Kubernetes review runtime, slice 1). */
class RunUnitSpecFetchTest {

    private static final UnitInputs INPUTS = new UnitInputs("inputs", "/inputs", Map.of("context.md", "TEST"));

    private static ContainerSpec container(Map<String, String> environment, List<Mount> mounts) {
        return new ContainerSpec("acme/tool:1", List.of("run"), environment, mounts);
    }

    private static RunUnitSpec unit() {
        return new RunUnitSpec("run_1",
                container(Map.of("SPIRE_CLONE_SECRET", "TEST-token"),
                        List.of(Mount.writable("workspace", "/workspace"))),
                container(Map.of(), List.of(Mount.writable("workspace", "/workspace"))),
                container(Map.of(), List.of()),
                EnterpriseEnvironment.NONE, EgressPolicy.OFF, 1024, 1024, 1024, Duration.ofMinutes(1));
    }

    private static RunUnitSpec proxiedReviewUnit() {
        ContainerSpec init = container(Map.of("SPIRE_CLONE_SECRET", "TEST-token"),
                List.of(Mount.writable("workspace", "/workspace"), Mount.writable("inputs", "/inputs")));
        ContainerSpec agent = container(Map.of(),
                List.of(Mount.writable("workspace", "/workspace"), Mount.readOnly("inputs", "/inputs")));
        ContainerSpec modelProxy = new ContainerSpec("spire-model-proxy:TEST", List.of(),
                Map.of("SPIRE_MODEL_SECRET", "TEST-SECRET"), List.of());
        return new RunUnitSpec("run_1", init, agent, container(Map.of(), List.of()), EnterpriseEnvironment.NONE,
                new EgressPolicy(EgressPolicy.Mode.ENFORCE, Set.of("api.openai.com")), 1024, 1024, 1024,
                Duration.ofMinutes(1)).withModelProxy(modelProxy).withInputs(INPUTS);
    }

    @Test
    void aSpecBuiltBeforeTheComponentExistedHasNoFetchHosts() {
        assertEquals(Set.of(), unit().fetchHosts());
    }

    @Test
    void withFetchHostsKeepsEveryOtherComponent() {
        RunUnitSpec with = proxiedReviewUnit().withFetchHosts(Set.of("TEST-forge.example"));

        assertEquals(proxiedReviewUnit().inputs(), with.inputs());
        assertEquals(proxiedReviewUnit().modelProxy(), with.modelProxy());
        assertEquals(Set.of("test-forge.example"), with.fetchHosts());
    }

    @Test
    void theOtherWithersKeepTheFetchHosts() {
        RunUnitSpec with = proxiedReviewUnit().withFetchHosts(Set.of("test-forge.example"));

        assertEquals(with.fetchHosts(), with.withInit(with.init()).fetchHosts());
        assertEquals(with.fetchHosts(), with.withAgent(with.agent()).fetchHosts());
        assertEquals(with.fetchHosts(), with.withPublisher(with.publisher()).fetchHosts());
        assertEquals(with.fetchHosts(), with.withModelProxy(with.modelProxy()).fetchHosts());
        assertEquals(with.fetchHosts(), with.withInputs(with.inputs()).fetchHosts());
    }

    @Test
    void aFetchHostThatIsNotABareHostNameIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> unit().withFetchHosts(Set.of("https://TEST-forge.example/x")));
    }

    @Test
    void initBehindTheFetchProxyGetsTheProxyAndTheDeploymentsTrustOnly() {
        Map<String, String> env = unit().environmentBehindFetch(unit().init());

        assertEquals("http://spire-fetch:8889", env.get("HTTPS_PROXY"));
        assertEquals("http://spire-fetch:8889", env.get("https_proxy"));
        assertEquals("http://spire-fetch:8889", env.get("HTTP_PROXY"));
        assertEquals("", env.get("NO_PROXY"));
        assertEquals("TEST-token", env.get("SPIRE_CLONE_SECRET"));
    }

    @Test
    void theDeploymentsOwnProxyIsNotPassedToInit() {
        EnterpriseEnvironment corporate = new EnterpriseEnvironment(List.of(), Map.of(
                "HTTPS_PROXY", "http://proxy.acme.example:3128", "NO_PROXY", "forge.acme.example"));
        RunUnitSpec spec = new RunUnitSpec("run_1", unit().init(), unit().agent(), unit().publisher(), corporate,
                EgressPolicy.OFF, 1024, 1024, 1024, Duration.ofMinutes(1));

        Map<String, String> env = spec.environmentBehindFetch(spec.init());

        assertEquals("http://spire-fetch:8889", env.get("HTTPS_PROXY"));
        assertEquals("", env.get("NO_PROXY"));
    }

    @Test
    void aContainerThatSetsAProxyVariableItselfIsRefusedBehindTheFetchProxy() {
        ContainerSpec own = container(Map.of("HTTPS_PROXY", "http://TEST-elsewhere:1"), List.of());

        assertThrows(IllegalArgumentException.class, () -> unit().environmentBehindFetch(own));
    }

    @Test
    void theDefaultRuntimeRefusesNoBuildLane() {
        RunRuntime runtime = new RunRuntime() {
            @Override public RuntimeType type() { throw new UnsupportedOperationException(); }
            @Override public RuntimeCapabilities capabilities() { throw new UnsupportedOperationException(); }
            @Override public RunHandle create(RunUnitSpec spec) { throw new UnsupportedOperationException(); }
            @Override public void attach(RunHandle handle, LogChannel channel,
                                         java.util.function.Consumer<String> lines) { }
            @Override public void cancel(RunHandle handle) { }
            @Override public Finalization salvage(RunHandle handle) { throw new UnsupportedOperationException(); }
            @Override public void destroy(RunHandle handle) { }
            @Override public void steer(RunHandle handle, String instruction) { }
            @Override public List<RunHandle> discoverUnits() { return List.of(); }
            @Override public Duration drainWindow() { return Duration.ZERO; }
        };

        assertEquals(Optional.empty(), runtime.buildLaneRefusal());
        assertTrue(runtime.buildLaneRefusal().isEmpty());
    }
}
