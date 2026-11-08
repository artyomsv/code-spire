package dev.codespire.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A unit with a model proxy (harness reviews step 3): the model proxy holds the credential and is the one container
 * behind the egress proxy; the agent beside it gets the deployment's trust and no way out.
 */
class RunUnitSpecTest {

    private static final String BUNDLE = "/etc/spire/ca-bundle.crt";

    private static final EgressPolicy ENFORCE = new EgressPolicy(EgressPolicy.Mode.ENFORCE, Set.of("api.openai.com"));

    private static final List<String> PROXY_NAMES =
            List.of("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy", "NO_PROXY", "no_proxy");

    private static ContainerSpec container(Map<String, String> environment, List<Mount> mounts) {
        return new ContainerSpec("acme/tool:1", List.of("run"), environment, mounts);
    }

    private static ContainerSpec modelProxy(Map<String, String> environment, List<Mount> mounts) {
        return new ContainerSpec("spire-model-proxy:TEST", List.of(), environment, mounts);
    }

    private static ContainerSpec modelProxy() {
        return modelProxy(Map.of("SPIRE_MODEL_SECRET", "TEST-SECRET"), List.of());
    }

    private static RunUnitSpec unit(EnterpriseEnvironment enterprise, EgressPolicy egress) {
        return new RunUnitSpec("run_1",
                container(Map.of("SPIRE_CLONE_SECRET", "TEST-read-token"), List.of(Mount.writable("workspace", "/workspace"))),
                container(Map.of("OPENAI_API_KEY", "TEST-PLACEHOLDER"),
                        List.of(Mount.writable("workspace", "/workspace"), Mount.writable("handoff", "/handoff"))),
                container(Map.of("SPIRE_GIT_SECRET", "TEST-push-token"), List.of(Mount.readOnly("handoff", "/handoff"))),
                enterprise, egress, 1024, 1024, 1024, Duration.ofMinutes(1));
    }

    private static EnterpriseEnvironment corporate() {
        return new EnterpriseEnvironment(
                List.of(new HostMount("/opt/acme/ca.crt", BUNDLE)),
                Map.of("SSL_CERT_FILE", BUNDLE,
                        "HTTPS_PROXY", "http://proxy.acme.example:3128",
                        "NO_PROXY", "forge.acme.example"));
    }

    @Test
    void aProxiedAgentHasNoProxyVariablesAndTheModelProxyHasThem() {
        RunUnitSpec spec = unit(corporate(), ENFORCE).withModelProxy(modelProxy());

        Map<String, String> agent = spec.environmentFor(spec.agent());
        for (String name : PROXY_NAMES) {
            assertFalse(agent.containsKey(name), "the agent of a proxied unit must have no " + name + ": " + agent.keySet());
        }
        assertEquals(BUNDLE, agent.get("SSL_CERT_FILE"), "the agent still trusts the deployment's bundle");
        assertEquals("TEST-PLACEHOLDER", agent.get("OPENAI_API_KEY"), "and keeps its own environment");

        Map<String, String> proxy = spec.environmentFor(spec.modelProxy());
        for (String name : List.of("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy")) {
            assertEquals(EgressPolicy.PROXY_ADDRESS, proxy.get(name), name + " of the model proxy");
        }
        assertEquals("", proxy.get("NO_PROXY"));
        assertEquals("", proxy.get("no_proxy"));
        assertEquals(BUNDLE, proxy.get("SSL_CERT_FILE"), "the model proxy builds its trust store from the bundle");
        assertEquals("TEST-SECRET", proxy.get("SPIRE_MODEL_SECRET"));
        assertEquals("http://spire-model:8080", RunUnitSpec.MODEL_PROXY_ADDRESS);
        assertEquals("spire-model", RunUnitSpec.MODEL_PROXY_ALIAS);
    }

    @Test
    void anUnproxiedAgentKeepsThem() {
        RunUnitSpec spec = unit(corporate(), ENFORCE);

        assertNull(spec.modelProxy());
        Map<String, String> agent = spec.environmentFor(spec.agent());
        assertEquals(EgressPolicy.PROXY_ADDRESS, agent.get("HTTPS_PROXY"), "behind the egress proxy, as before");
        assertEquals("", agent.get("NO_PROXY"));
        assertEquals("http://proxy.acme.example:3128", spec.environmentFor(spec.publisher()).get("HTTPS_PROXY"),
                "the other containers still get the deployment's environment whole");
    }

    @Test
    void aModelProxyNeedsAnEgressNetwork() {
        // With egress off there is no egress proxy and no model network, so the model proxy would have no way out
        // and the agent's model calls would fail with nothing naming why.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> unit(corporate(), EgressPolicy.OFF).withModelProxy(modelProxy()));
        assertTrue(refused.getMessage().contains("egress"), refused.getMessage());
    }

    @Test
    void aModelProxyMayNotCollideWithTheDeploymentEnvironment() {
        ContainerSpec colliding = modelProxy(Map.of("SPIRE_MODEL_SECRET", "TEST-SECRET", "HTTPS_PROXY", "http://elsewhere:1"),
                List.of());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> unit(corporate(), ENFORCE).withModelProxy(colliding));
        assertTrue(refused.getMessage().contains("HTTPS_PROXY"), refused.getMessage());
        assertTrue(refused.getMessage().contains("model proxy"), "named by role: " + refused.getMessage());

        ContainerSpec trust = modelProxy(Map.of("SSL_CERT_FILE", "/elsewhere.crt"), List.of());
        assertThrows(IllegalArgumentException.class, () -> unit(corporate(), ENFORCE).withModelProxy(trust),
                "a trust variable the deployment sets collides too");
    }

    @Test
    void aModelProxyTakesNoWorkspaceOrHandoffMount() {
        for (Mount mount : List.of(Mount.readOnly("workspace", "/workspace"), Mount.readOnly("handoff", "/handoff"),
                Mount.writable("scratch", "/scratch"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> unit(corporate(), ENFORCE).withModelProxy(modelProxy(Map.of(), List.of(mount))),
                    "the model proxy holds the credential and may mount nothing of the unit's: " + mount);
        }
    }

    @Test
    void theWithersKeepTheModelProxy() {
        ContainerSpec proxy = modelProxy();
        RunUnitSpec spec = unit(corporate(), ENFORCE).withModelProxy(proxy);
        ContainerSpec other = container(Map.of(), List.of(Mount.writable("workspace", "/workspace")));

        assertSame(proxy, spec.withInit(other).modelProxy());
        assertSame(proxy, spec.withAgent(other).modelProxy());
        assertSame(proxy, spec.withPublisher(container(Map.of(), List.of())).modelProxy());
        assertSame(proxy, spec.withInputs(null).modelProxy());
        assertEquals(spec.egress(), spec.withModelProxy(null).egress());
        assertNull(spec.withModelProxy(null).modelProxy());
    }
}
