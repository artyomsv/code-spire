package dev.codespire.runworker;

import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.HostMount;
import dev.codespire.runtime.RegistryCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deployment's corporate configuration, read from the worker's own config and nowhere else
 * (FR-F14).
 *
 * <p>Every value here is optional, so most of these assert what happens when one is absent, wrong
 * or half-supplied — the cases an operator actually meets. A configuration class that only works
 * when fully and correctly filled in is one that fails at dispatch instead of at startup.
 */
class EnterpriseEnvironmentConfigTest {

    private static final String PROXY = "http://proxy.acme.example:3128";

    private static EnterpriseEnvironmentConfig config() {
        EnterpriseEnvironmentConfig config = new EnterpriseEnvironmentConfig();
        // Every Optional is set, deliberately. An unset @ConfigProperty field is null rather than
        // empty, and the repeated lesson in this repository is that a fake which does not answer a
        // newly-added collaborator fails somewhere unrelated -- here it would be an NPE inside
        // resolve() reported as a configuration fault.
        config.caBundlePath = Optional.empty();
        config.httpProxy = Optional.empty();
        config.httpsProxy = Optional.empty();
        config.noProxy = Optional.empty();
        config.registryHost = Optional.empty();
        config.registryUsername = Optional.empty();
        config.registrySecret = Optional.empty();
        return config;
    }

    /** The overwhelmingly common deployment: nothing corporate, and nothing added to a unit. */
    @Test
    void anUnconfiguredWorkerAddsNothingToARunUnit() {
        EnterpriseEnvironmentConfig config = config();
        config.resolve();

        assertSame(EnterpriseEnvironment.NONE, config.environment());
        assertTrue(config.registryCredential().isEmpty());
    }

    /** A key present but blank is unset. Compose writes an empty string for an unset variable. */
    @Test
    void aBlankValueIsTreatedAsAbsentRatherThanAsAnEmptyProxy() {
        EnterpriseEnvironmentConfig config = config();
        config.httpsProxy = Optional.of("   ");
        config.caBundlePath = Optional.of("");
        config.resolve();

        assertSame(EnterpriseEnvironment.NONE, config.environment());
    }

    @Test
    void aCaBundleBecomesAReadOnlyHostMountAndTheThreeVariablesThatReadIt(@TempDir Path dir)
            throws IOException {
        Path bundle = Files.writeString(dir.resolve("ca.crt"), "-----BEGIN CERTIFICATE-----\n");

        EnterpriseEnvironmentConfig config = config();
        config.caBundlePath = Optional.of(bundle.toString());
        config.resolve();

        assertEquals(java.util.List.of(new HostMount(bundle.toString(), "/etc/spire/ca-bundle.crt")),
                config.environment().mounts());

        Map<String, String> environment = config.environment().environment();
        assertEquals("/etc/spire/ca-bundle.crt", environment.get("SSL_CERT_FILE"));
        assertEquals("/etc/spire/ca-bundle.crt", environment.get("GIT_SSL_CAINFO"));
        assertEquals("/etc/spire/ca-bundle.crt", environment.get("NODE_EXTRA_CA_CERTS"),
                "Node reads neither of the others, and the Codex arm is Node");
    }

    /**
     * The mount point is not the image's own certificate store.
     *
     * <p>Overwriting it would make the operator's file the container's ENTIRE trust set, so a
     * bundle holding only the corporate root breaks every public TLS call while the internal forge
     * keeps working — a failure that reads as an outage at the model provider.
     */
    @Test
    void theBundleIsNotMountedOverTheImagesOwnCertificateStore(@TempDir Path dir) throws IOException {
        Path bundle = Files.writeString(dir.resolve("ca.crt"), "x");

        EnterpriseEnvironmentConfig config = config();
        config.caBundlePath = Optional.of(bundle.toString());
        config.resolve();

        assertFalse(config.environment().mounts().stream()
                        .anyMatch(mount -> mount.path().startsWith("/etc/ssl/certs")),
                "mounting over the system store replaces it rather than adding to it");
    }

    /**
     * The trap this check exists for: a bind source that does not exist is not an error in every
     * runtime — it can be created as an empty directory — so a typo produces three variables
     * pointing at a directory and a TLS failure that names neither the mount nor the path setting.
     */
    @Test
    void aMissingCaBundleIsRefusedAtStartupRatherThanMountedAsADirectory(@TempDir Path dir) {
        EnterpriseEnvironmentConfig config = config();
        config.caBundlePath = Optional.of(dir.resolve("not-here.crt").toString());

        IllegalStateException refused = assertThrows(IllegalStateException.class, config::resolve);

        assertTrue(refused.getMessage().contains("not-here.crt"),
                "the message must carry the path the operator typed: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("ca-bundle-path"),
                "and the setting to correct: " + refused.getMessage());
    }

    /** A directory passes an exists() check and fails every TLS call, so it is refused too. */
    @Test
    void aDirectoryIsNotABundle(@TempDir Path dir) {
        EnterpriseEnvironmentConfig config = config();
        config.caBundlePath = Optional.of(dir.toString());

        assertThrows(IllegalStateException.class, config::resolve);
    }

    /**
     * Both spellings, because tools disagree and several read only one. A single spelling produces
     * a unit where some calls are proxied and some are not, which presents as an intermittent
     * network fault rather than as configuration.
     */
    @Test
    void proxyVariablesAreSetInBothCases() {
        EnterpriseEnvironmentConfig config = config();
        config.httpProxy = Optional.of(PROXY);
        config.httpsProxy = Optional.of(PROXY);
        config.noProxy = Optional.of("forge.acme.example");
        config.resolve();

        Map<String, String> environment = config.environment().environment();
        assertEquals(PROXY, environment.get("HTTP_PROXY"));
        assertEquals(PROXY, environment.get("http_proxy"));
        assertEquals(PROXY, environment.get("HTTPS_PROXY"));
        assertEquals(PROXY, environment.get("https_proxy"));
        assertEquals("forge.acme.example", environment.get("NO_PROXY"));
        assertEquals("forge.acme.example", environment.get("no_proxy"));
    }

    @Test
    void aRegistryCredentialIsReadWhenAllThreePartsArePresent() {
        EnterpriseEnvironmentConfig config = config();
        config.registryHost = Optional.of("registry.acme.example");
        config.registryUsername = Optional.of("spire");
        config.registrySecret = Optional.of("TEST-registry-secret");
        config.resolve();

        assertEquals(Optional.of(new RegistryCredential(
                        "registry.acme.example", "spire", "TEST-registry-secret")),
                config.registryCredential());
    }

    /**
     * Half a credential is worse than none: it falls back to an anonymous pull, so a private image
     * is reported as NOT FOUND and the operator goes to check the image reference rather than the
     * password.
     */
    @Test
    void aHalfConfiguredRegistryIsRefusedRatherThanFallingBackToAnonymous() {
        EnterpriseEnvironmentConfig config = config();
        config.registryHost = Optional.of("registry.acme.example");
        config.registryUsername = Optional.of("spire");

        IllegalStateException refused = assertThrows(IllegalStateException.class, config::resolve);

        assertTrue(refused.getMessage().contains("secret"), refused.getMessage());
    }

    /** The registry credential is never part of the environment handed to a container. */
    @Test
    void theRegistryCredentialNeverReachesTheUnitsEnvironment() {
        EnterpriseEnvironmentConfig config = config();
        config.registryHost = Optional.of("registry.acme.example");
        config.registryUsername = Optional.of("spire");
        config.registrySecret = Optional.of("TEST-registry-secret");
        config.httpsProxy = Optional.of(PROXY);
        config.resolve();

        assertFalse(config.environment().environment().values().contains("TEST-registry-secret"),
                "it authenticates a pull; a container that carries it prints it in docker inspect");
        assertFalse(config.environment().toString().contains("TEST-registry-secret"));
    }
    /**
     * The validation must run at STARTUP, and only the annotation says so.
     *
     * <p>Every case above calls {@code resolve()} directly, so deleting the {@code @Observes}
     * declaration leaves them all green while an {@code @ApplicationScoped} bean goes back to
     * being created lazily -- on the first dispatch. A mistyped bundle path would then be found
     * after a run had been accepted rather than while an operator was still at the terminal that
     * could fix it. Reflection rather than a boot test, for the reason
     * {@code ScheduledWorkIsDeclaredTest} gives: the declaration is the half that can be deleted
     * by accident.
     */
    @Test
    void theConfigurationIsValidatedAtStartupRatherThanOnFirstUse() throws NoSuchMethodException {
        java.lang.reflect.Method check =
                EnterpriseEnvironmentConfig.class.getDeclaredMethod("check", io.quarkus.runtime.StartupEvent.class);

        assertTrue(check.getParameters()[0].isAnnotationPresent(jakarta.enterprise.event.Observes.class),
                "without @Observes StartupEvent the bean is created lazily and a bad path is found "
                        + "by the first dispatch instead of by the operator starting the worker");
    }
}