package dev.codespire.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The corporate environment is a property of the UNIT, not of any one container (FR-F14).
 *
 * <p>Every assertion here is about that placement rather than about the values. A per-container
 * field would satisfy every reasonable-looking test while leaving the real requirement — that no
 * container is missed — to whoever writes the builder next.
 */
class EnterpriseEnvironmentTest {

    private static final String BUNDLE = "/etc/spire/ca-bundle.crt";

    private static ContainerSpec container(Map<String, String> environment, List<Mount> mounts) {
        return new ContainerSpec("acme/tool:1", List.of("run"), environment, mounts);
    }

    private static RunUnitSpec unit(EnterpriseEnvironment enterprise) {
        return new RunUnitSpec("run_1",
                container(Map.of("SPIRE_CLONE_SECRET", "read-token"),
                        List.of(Mount.writable("workspace", "/workspace"))),
                container(Map.of("OPENAI_API_KEY", "model-key"),
                        List.of(Mount.writable("workspace", "/workspace"),
                                Mount.writable("handoff", "/handoff"))),
                container(Map.of("SPIRE_GIT_SECRET", "push-token"),
                        List.of(Mount.readOnly("handoff", "/handoff"))),
                enterprise, 1024, 1024, Duration.ofMinutes(1));
    }

    private static EnterpriseEnvironment corporate() {
        return new EnterpriseEnvironment(
                List.of(new HostMount("/opt/acme/ca.crt", BUNDLE)),
                Map.of("SSL_CERT_FILE", BUNDLE,
                        "GIT_SSL_CAINFO", BUNDLE,
                        "NODE_EXTRA_CA_CERTS", BUNDLE,
                        "HTTPS_PROXY", "http://proxy.acme.example:3128",
                        "NO_PROXY", "forge.acme.example,localhost"));
    }

    /**
     * The init container is the one that matters most and is easiest to miss: without the bundle
     * its clone fails at the forge, and a clone failure presents as a bad credential rather than as
     * a trust-store problem. Asserted for all three at once, because "two of three" is the shape a
     * per-container implementation produces.
     */
    @Test
    void aCaBundleIsMountedIntoEveryContainerOfTheUnit() {
        RunUnitSpec spec = unit(corporate());

        assertEquals(List.of(new HostMount("/opt/acme/ca.crt", BUNDLE)), spec.hostMounts());

        for (ContainerSpec container : List.of(spec.init(), spec.agent(), spec.publisher())) {
            Map<String, String> environment = spec.environmentFor(container);
            assertEquals(BUNDLE, environment.get("SSL_CERT_FILE"), "OpenSSL, so curl");
            assertEquals(BUNDLE, environment.get("GIT_SSL_CAINFO"), "git, so the clone and the push");
            assertEquals(BUNDLE, environment.get("NODE_EXTRA_CA_CERTS"), "Node, so the Codex arm");
        }
    }

    /**
     * The no-proxy list travels with the proxy or the deployment is worse off than with neither:
     * an internal forge reachable only directly becomes a clone that hangs to the init timeout.
     */
    @Test
    void proxyVariablesReachTheAgentAndThePublisher() {
        RunUnitSpec spec = unit(corporate());

        for (ContainerSpec container : List.of(spec.agent(), spec.publisher())) {
            Map<String, String> environment = spec.environmentFor(container);
            assertEquals("http://proxy.acme.example:3128", environment.get("HTTPS_PROXY"));
            assertEquals("forge.acme.example,localhost", environment.get("NO_PROXY"));
        }
    }

    /** The merge adds; it does not replace what the role was given. */
    @Test
    void aContainerKeepsItsOwnEnvironmentAlongsideTheDeploymentsOwn() {
        RunUnitSpec spec = unit(corporate());

        assertEquals("push-token", spec.environmentFor(spec.publisher()).get("SPIRE_GIT_SECRET"));
        assertEquals("model-key", spec.environmentFor(spec.agent()).get("OPENAI_API_KEY"));
        assertFalse(spec.environmentFor(spec.publisher()).containsKey("OPENAI_API_KEY"),
                "the publisher never sees the model key, and merging must not change that");
    }

    /**
     * Refused rather than resolved by a precedence rule, because the names that collide in practice
     * are the credentials each role is handed: letting the deployment win blanks the publisher's
     * push token, and letting the container win lets the agent bypass a mandatory proxy. Both are
     * silent.
     */
    @Test
    void aDeploymentVariableMayNotSilentlyReplaceAContainersOwn() {
        EnterpriseEnvironment collides = new EnterpriseEnvironment(List.of(),
                Map.of("SPIRE_GIT_SECRET", "someone-elses-token"));

        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> unit(collides));

        assertTrue(refused.getMessage().contains("SPIRE_GIT_SECRET"), refused.getMessage());
        assertTrue(refused.getMessage().contains("silently ignored"), refused.getMessage());
    }

    /** A bundle configured at /workspace would replace the agent's work tree with a host file. */
    @Test
    void aHostMountMayNotShadowAVolumeTheUnitAlreadyMounts() {
        EnterpriseEnvironment shadows = new EnterpriseEnvironment(
                List.of(new HostMount("/opt/acme/ca.crt", "/workspace")), Map.of());

        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> unit(shadows));

        assertTrue(refused.getMessage().contains("/workspace"), refused.getMessage());
    }

    /**
     * There is no writable host mount to construct.
     *
     * <p>Asserted by reflection rather than by trying to build one, because the point is that the
     * type has no such component: a test that merely built a read-only mount would pass just as
     * well against a record carrying a {@code readOnly} flag somebody could set to false.
     */
    @Test
    void aHostMountCannotBeWritable() {
        List<String> components = java.util.Arrays.stream(HostMount.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertEquals(List.of("hostPath", "path"), components,
                "a host bind reaches the worker host and the agent runs untrusted output; "
                        + "writable must not be expressible, not merely defaulted");
    }

    /** A proxy URL may carry basic auth, so the values are not printable even here. */
    @Test
    void theEnvironmentIsRedactedWhenPrinted() {
        String printed = new EnterpriseEnvironment(List.of(),
                Map.of("HTTPS_PROXY", "http://user:hunter2@proxy.acme.example:3128")).toString();

        assertFalse(printed.contains("hunter2"), printed);
        assertTrue(printed.contains("HTTPS_PROXY"), "the name stays, or the log says nothing useful");
    }

    /** The registry secret is masked for the same reason, and its host and user are not. */
    @Test
    void aRegistryCredentialIsMaskedWhenPrinted() {
        String printed = new RegistryCredential("registry.acme.example", "spire", "hunter2").toString();

        assertFalse(printed.contains("hunter2"), printed);
        assertTrue(printed.contains("registry.acme.example"), printed);
        assertTrue(printed.contains("spire"), "an operator needs to see which identity was refused");
    }

    /** Half a credential is worse than none; it falls back to anonymous and reports a 404. */
    @Test
    void aRegistryCredentialWithNoSecretIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new RegistryCredential("registry.acme.example", "spire", " "));
    }

    /** The ordinary deployment carries none of this and constructs the same way. */
    @Test
    void theOrdinaryDeploymentAddsNothing() {
        RunUnitSpec spec = unit(EnterpriseEnvironment.NONE);

        assertTrue(spec.hostMounts().isEmpty());
        assertEquals(spec.agent().environment(), spec.environmentFor(spec.agent()));
    }
}
