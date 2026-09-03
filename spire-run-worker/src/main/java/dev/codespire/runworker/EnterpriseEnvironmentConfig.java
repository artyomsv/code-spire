package dev.codespire.runworker;

import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.HostMount;
import dev.codespire.runtime.RegistryCredential;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The deployment's corporate environment, read once and handed to every run unit (FR-F14).
 *
 * <p><b>None of it comes from the command, and that is a boundary rather than a convenience.</b> A
 * proxy address, a CA bundle path and a registry password are facts about the machine the worker
 * runs on; the orchestrator that issues a run has no way to know them and no business carrying
 * them. Putting them on {@code ExecuteRun} would also put a registry password on the Kafka bus for
 * every run, where ADR-014 covers the topic with short retention rather than encryption.
 *
 * <p>Everything here is optional and absent means absent — there is no default proxy and no default
 * CA path, because a wrong value is worse than none: a bundle that is not the operator's silently
 * replaces the container's trust store.
 */
@ApplicationScoped
public class EnterpriseEnvironmentConfig {

    private static final Logger LOG = Logger.getLogger(EnterpriseEnvironmentConfig.class);

    /**
     * Where the bundle is mounted inside every container.
     *
     * <p>Its own path, NOT over {@code /etc/ssl/certs/ca-certificates.crt}. Overwriting the image's
     * own store is the shorter route and it makes the operator's file the container's ENTIRE trust
     * set, so a bundle holding only the corporate root breaks every public TLS call — the model
     * API included — while the internal forge keeps working. That failure looks like an outage at
     * the provider, not like a configuration mistake.
     */
    static final String BUNDLE_PATH = "/etc/spire/ca-bundle.crt";

    /**
     * The three names that actually change behaviour, one per TLS stack in a run unit.
     *
     * <p>{@code SSL_CERT_FILE} is OpenSSL, so it covers curl and anything linked against it;
     * {@code GIT_SSL_CAINFO} is git, which is what the init clone and the publisher's push use;
     * {@code NODE_EXTRA_CA_CERTS} is Node, which is what the Codex arm runs on and which ignores
     * both of the others. Setting one and expecting the rest to follow is the mistake this list
     * exists to prevent: git would clone and the agent's first model call would still fail.
     *
     * <p>The first two REPLACE the trust store rather than adding to it, which is why the mounted
     * file must be a COMPLETE bundle — the corporate root appended to the public roots, not the
     * corporate root alone. Documented in {@code .env.example} where an operator sets the path.
     */
    private static final List<String> CA_VARIABLES =
            List.of("SSL_CERT_FILE", "GIT_SSL_CAINFO", "NODE_EXTRA_CA_CERTS");

    @ConfigProperty(name = "spire.run.ca-bundle-path")
    Optional<String> caBundlePath;

    @ConfigProperty(name = "spire.run.http-proxy")
    Optional<String> httpProxy;

    @ConfigProperty(name = "spire.run.https-proxy")
    Optional<String> httpsProxy;

    /**
     * Hosts that must NOT go through the proxy.
     *
     * <p>An internal forge and a self-hosted model endpoint are routinely reachable only directly,
     * and a proxy with no exception list turns a working deployment into one where the clone hangs
     * until the init timeout. It is carried with the other two rather than left to the operator's
     * image because the three are one setting: two of them without the third is a misconfiguration.
     */
    @ConfigProperty(name = "spire.run.no-proxy")
    Optional<String> noProxy;

    @ConfigProperty(name = "spire.run.registry-host")
    Optional<String> registryHost;

    @ConfigProperty(name = "spire.run.registry-username")
    Optional<String> registryUsername;

    @ConfigProperty(name = "spire.run.registry-secret")
    Optional<String> registrySecret;

    private EnterpriseEnvironment resolved;

    private RegistryCredential registry;

    @PostConstruct
    void resolve() {
        this.resolved = buildEnvironment();
        this.registry = buildRegistry();
    }

    /**
     * Forces this bean to exist at startup, so a bad value is a refusal to start.
     *
     * <p>{@code @PostConstruct} alone would not do it. An {@code @ApplicationScoped} bean is
     * created LAZILY -- on the first method call through its client proxy -- so a mistyped bundle
     * path would be discovered by the first dispatch instead: after a run had been accepted, and
     * with the operator who could still fix it long gone. Every other guard in this service
     * (RunAckBudget, PublisherImageCheck, OrphanWatchdog) observes the same event for the same
     * reason.
     *
     * <p>The call is not decorative: reading a method through the proxy is what instantiates the
     * bean and therefore what runs the validation above.
     */
    void check(@Observes StartupEvent event) {
        environment();
    }

    public EnterpriseEnvironment environment() {
        return resolved;
    }

    /** Empty on an ordinary deployment; the runtime pulls anonymously. */
    public Optional<RegistryCredential> registryCredential() {
        return Optional.ofNullable(registry);
    }

    /**
     * The password embedded in a proxy URL, if there is one, so it can be redacted.
     *
     * <p><b>A new leak path arrives with this feature.</b> A corporate proxy URL may carry basic
     * auth, that URL is set in every container of every unit, and git and curl both quote the URL
     * they tried in their failure messages -- which become the run transcript and the failure
     * detail an operator reads. The per-run scrub cannot help by itself: it is built from the
     * credentials the COMMAND carries, and this one belongs to the deployment.
     *
     * <p>Only the password, never the whole URL. The host is what makes a proxy error legible, and
     * redacting it would leave an operator with a failure that names nothing.
     */
    public Optional<String> proxySecret() {
        return Stream.of(httpProxy, httpsProxy)
                .flatMap(Optional::stream)
                .map(EnterpriseEnvironmentConfig::passwordIn)
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * The password from a {@code scheme://user:password@host} URL.
     *
     * <p>Parsed by hand rather than with {@link java.net.URI}, because a password routinely
     * contains characters URI rejects and a thrown parse would silently mean "no secret to
     * redact" -- turning an unparseable password into an unredacted one.
     */
    private static Optional<String> passwordIn(String url) {
        int scheme = url.indexOf("://");
        int at = url.lastIndexOf("@");
        if (scheme < 0 || at < scheme) {
            return Optional.empty();
        }
        String userInfo = url.substring(scheme + 3, at);
        int colon = userInfo.indexOf(":");
        if (colon < 0 || colon == userInfo.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(userInfo.substring(colon + 1));
    }

    private EnterpriseEnvironment buildEnvironment() {
        List<HostMount> mounts = new ArrayList<>();
        Map<String, String> environment = new LinkedHashMap<>();

        caBundlePath.map(String::trim).filter(path -> !path.isEmpty()).ifPresent(path -> {
            requireReadableFile(path);
            mounts.add(new HostMount(path, BUNDLE_PATH));
            CA_VARIABLES.forEach(name -> environment.put(name, BUNDLE_PATH));
            LOG.infof("run units trust the CA bundle at %s, mounted read-only at %s", path, BUNDLE_PATH);
        });

        // Both spellings. Tools disagree on case and several read only one: curl takes the lower
        // case, most JVM and Go tooling the upper, and git reads the lower. Setting one spelling
        // produces a unit where some calls are proxied and some are not, which presents as an
        // intermittent network fault rather than as configuration.
        putBothCases(environment, "HTTP_PROXY", httpProxy);
        putBothCases(environment, "HTTPS_PROXY", httpsProxy);
        putBothCases(environment, "NO_PROXY", noProxy);

        if (mounts.isEmpty() && environment.isEmpty()) {
            return EnterpriseEnvironment.NONE;
        }
        return new EnterpriseEnvironment(List.copyOf(mounts), Map.copyOf(environment));
    }

    private static void putBothCases(Map<String, String> environment, String name, Optional<String> value) {
        value.map(String::trim).filter(v -> !v.isEmpty()).ifPresent(v -> {
            environment.put(name, v);
            environment.put(name.toLowerCase(java.util.Locale.ROOT), v);
        });
    }

    /**
     * A configured bundle that is not a readable file is a startup refusal.
     *
     * <p><b>Docker CREATES a missing bind source as an empty directory</b> rather than failing, so
     * without this check a typo in the path produces a unit where every container has a DIRECTORY
     * at {@code /etc/spire/ca-bundle.crt} and three environment variables pointing at it. Nothing
     * reports a mount problem; the run fails at its first TLS call with an error about the
     * certificate store, and the path in the message is the one the operator meant to set.
     *
     * <p>Refusing at startup rather than at dispatch because this is deployment configuration: an
     * operator restarting a worker can still fix it, whereas a run failing on it has already been
     * dispatched, charged and lost.
     */
    private static void requireReadableFile(String path) {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IllegalStateException("spire.run.ca-bundle-path is set to \"" + path
                    + "\", which is not a readable file on this host. A missing bind source is"
                    + " not an error in every runtime -- it can be created as an empty directory --"
                    + " so every TLS call in every run would fail against it.");
        }
    }

    /**
     * All three parts or none.
     *
     * <p>A half-configured credential is the dangerous shape: a host and username with no secret
     * would authenticate as nobody and fall back to an anonymous pull, so a private image would
     * fail with a not-found rather than with a credential error, and the operator would look at the
     * image reference.
     */
    private RegistryCredential buildRegistry() {
        String host = trimmed(registryHost);
        String username = trimmed(registryUsername);
        String secret = trimmed(registrySecret);
        if (host == null && username == null && secret == null) {
            return null;
        }
        if (host == null || username == null || secret == null) {
            throw new IllegalStateException("a private registry needs spire.run.registry-host,"
                    + " -username and -secret together; "
                    + (host == null ? "host" : username == null ? "username" : "secret")
                    + " is missing, and a partial credential falls back to an anonymous pull that"
                    + " reports a private image as not found");
        }
        return new RegistryCredential(host, username, secret);
    }

    private static String trimmed(Optional<String> value) {
        return value.map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
    }
}
