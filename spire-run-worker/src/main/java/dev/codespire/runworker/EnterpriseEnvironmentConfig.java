package dev.codespire.runworker;

import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.HostMount;
import dev.codespire.runtime.RegistryCredential;
import dev.codespire.secrets.SecretScrub;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
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

    /** Every PEM spelling a private key takes; one of them in a trust bundle is a leak. */
    private static final java.util.regex.Pattern PRIVATE_KEY_BLOCK = java.util.regex.Pattern
            .compile("-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----");

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
     * Every credential a proxy URL carries, so every one of them can be redacted.
     *
     * <p><b>A new leak path arrives with this feature.</b> A corporate proxy URL may carry basic
     * auth, that URL is set in every container of every unit, and git and curl both quote the URL
     * they tried in their failure messages -- which become the run transcript and the failure
     * detail an operator reads. The per-run scrub cannot help by itself: it is built from the
     * credentials the COMMAND carries, and this one belongs to the deployment.
     *
     * <p><b>Every one, not the first.</b> The http and https proxies are separately configurable,
     * so a rotation or two upstreams gives them different passwords -- and the one that survived a
     * {@code findFirst} was whichever came first in the stream, while the https credential is the
     * one every forge and model call actually uses.
     *
     * <p><b>With its username</b>, because the native form of this credential is
     * {@code Proxy-Authorization: Basic base64(user:password)} and a verbose curl prints that
     * header. Pairing the password with the SCM username -- the only username the scrub used to
     * know -- produced a base64 form that can never appear on any wire, so the "three forms, not
     * one" contract was false for exactly this credential.
     *
     * <p>Only the credential, never the whole URL. The host is what makes a proxy error legible,
     * and redacting it would leave an operator with a failure that names nothing.
     *
     * <p><b>Both spellings of every password: as the operator wrote it, and percent-decoded.</b>
     * Two credentials rather than one richer type, because {@code SecretScrub} already treats a
     * list as "every form of every secret" and a second entry costs one more string to match. The
     * two are different text in different places: the RAW one is the environment variable every
     * container carries and {@code printenv} prints into a transcript, and the DECODED one is what
     * a {@code Proxy-Authorization: Basic} header and a verbose {@code curl} print.
     */
    public List<SecretScrub.Credential> proxyCredentials() {
        return Stream.of(httpProxy, httpsProxy)
                .flatMap(Optional::stream)
                .map(EnterpriseEnvironmentConfig::credentialIn)
                .flatMap(Optional::stream)
                .flatMap(EnterpriseEnvironmentConfig::bothSpellings)
                .distinct()
                .toList();
    }

    /**
     * The credential as written, plus its decoded form when the two differ.
     *
     * <p>distinct() upstream collapses them back to one when the password carries no escapes, which
     * is the common case, so this costs nothing there.
     */
    private static Stream<SecretScrub.Credential> bothSpellings(SecretScrub.Credential asWritten) {
        String decoded = decode(asWritten.secret());
        if (decoded.equals(asWritten.secret())) {
            return Stream.of(asWritten);
        }
        return Stream.of(asWritten, new SecretScrub.Credential(asWritten.username(), decoded));
    }

    /**
     * The credential from a {@code [scheme://]user:password@host} URL.
     *
     * <p>Parsed by hand rather than with {@link java.net.URI}, because a password routinely
     * contains characters URI rejects and a thrown parse would silently mean "no secret to
     * redact" -- turning an unparseable password into an unredacted one.
     *
     * <p>Three shapes the first version got wrong, each of them a real operator input. The scheme
     * is OPTIONAL, because {@code user:pass@proxy:3128} is a form curl accepts and people write --
     * and requiring it meant the password was set in every container and scrubbed from nothing.
     * The search is bounded to the AUTHORITY, so an {@code @} in a path is not read as the end of
     * a userinfo that is not there. {@code lastIndexOf} stays: a password may itself contain an
     * {@code @}.
     *
     * <p><b>The secret is returned AS WRITTEN, and the decoded form is added separately by</b>
     * {@link #proxyCredentials()}. Returning only the decoded form was a real gap, and the reason
     * is easy to talk past: {@code SecretScrub} derives its URL-encoded form with
     * {@code URLEncoder}, which emits uppercase hex and {@code +} for a space. An operator who
     * wrote {@code p%2fss%20x} therefore had a decoded form ({@code p/ss x}) and a re-encoded form
     * ({@code p%2Fss+x}) that matched NEITHER the header nor the environment variable. Measured,
     * not reasoned about. The variable is what {@code printenv} prints into a run transcript a
     * viewer can read, so it is the spelling that most needs covering.
     */
    private static Optional<SecretScrub.Credential> credentialIn(String url) {
        int scheme = url.indexOf("://");
        String rest = scheme < 0 ? url : url.substring(scheme + 3);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        int at = authority.lastIndexOf('@');
        if (at < 0) {
            return Optional.empty();
        }
        String userInfo = authority.substring(0, at);
        int colon = userInfo.indexOf(':');
        if (colon < 0 || colon == userInfo.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new SecretScrub.Credential(decode(userInfo.substring(0, colon)),
                userInfo.substring(colon + 1)));
    }

    /**
     * Percent-decoded where that is what the value is, and returned unchanged where it is not.
     *
     * <p><b>It must never throw, and the reason is a defect this branch introduced.</b>
     * {@code URLDecoder} rejects a bare {@code %} — {@code 100%secure} and {@code pa%ss} both
     * throw — and a bare {@code %} is a legal password character an operator writes. That used to
     * be caught at boot by accident: the deleted startup refusal was the only caller of
     * {@link #proxyCredentials()}, so a URL like that failed loudly before any run existed.
     *
     * <p>With the refusal gone the first caller is {@code RunFailures.scrubFor}, and the throw
     * lands somewhere far worse. {@code RunLauncher} builds the transcript scrub AFTER
     * {@code runtime.create(unit)} has put the model key and the git write token into three
     * containers; {@code RunDispatcher}'s catch then calls {@code failures.of(...)}, which
     * re-enters this same code and throws a SECOND time, escaping the handler before its
     * {@code finally} runs. {@code registry.forget} never happens, and that dispatcher comment
     * says what follows: a credential-bearing sandbox permanently unreclaimable by the watchdog.
     * One mistyped character, every run, deterministically.
     *
     * <p>Returning the value unchanged is the right answer as well as the safe one: a {@code %}
     * the operator did not mean as an escape means the value already IS its own decoded form, and
     * {@link #bothSpellings} then collapses to the single entry that is true.
     *
     * <p><b>{@code +} is protected first.</b> {@code URLDecoder} is a FORM decoder and turns
     * {@code +} into a space, which no URI userinfo means by it — curl percent-decodes only. So a
     * password {@code a+b} would yield a "decoded" form {@code a b} that appears on no wire while
     * the real header form went uncovered. Escaping it first makes the decode percent-only.
     */
    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notPercentEncoded) {
            // Deliberately not logged: the exception message quotes two characters of the
            // password ("Error at index 0 in: \"ss\""), and this class exists to keep exactly
            // that out of a log.
            return value;
        }
    }

    private EnterpriseEnvironment buildEnvironment() {
        List<HostMount> mounts = new ArrayList<>();
        Map<String, String> environment = new LinkedHashMap<>();

        caBundlePath.map(String::trim).filter(path -> !path.isEmpty()).ifPresent(path -> {
            String absolute = requireUsableBundle(path);
            mounts.add(new HostMount(absolute, BUNDLE_PATH));
            CA_VARIABLES.forEach(name -> environment.put(name, BUNDLE_PATH));
            LOG.infof("run units trust the CA bundle at %s, mounted read-only at %s", path, BUNDLE_PATH);
        });

        // Both spellings. Tools disagree on case and several read only one: curl takes the lower
        // case, most JVM and Go tooling the upper, and git reads the lower. Setting one spelling
        // produces a unit where some calls are proxied and some are not, which presents as an
        // intermittent network fault rather than as configuration.
        putBothCases(environment, "HTTP_PROXY", trimmed(httpProxy));
        putBothCases(environment, "HTTPS_PROXY", trimmed(httpsProxy));
        putBothCases(environment, "NO_PROXY", trimmed(noProxy));
        // A short proxy password used to be a startup REFUSAL here, and the refusal is gone with
        // the premise it rested on: SecretScrub skipped anything under its floor, so a short
        // password reached every failure detail verbatim and refusing was the only protection.
        // It no longer skips, so the password is scrubbed like any other and SecretScrub warns
        // once about the readability cost. Keeping the refusal would have meant asserting a new
        // rule about what an operator may configure -- "a proxy password must be eight characters"
        // -- which this deployment has no standing to make and which would block a working proxy.

        if (mounts.isEmpty() && environment.isEmpty()) {
            return EnterpriseEnvironment.NONE;
        }
        return new EnterpriseEnvironment(List.copyOf(mounts), Map.copyOf(environment));
    }

    private static void putBothCases(Map<String, String> environment, String name, String value) {
        if (value == null) {
            return;
        }
        environment.put(name, value);
        environment.put(name.toLowerCase(Locale.ROOT), value);
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
    private static String requireUsableBundle(String path) {
        // Absolute, and absolute as the DAEMON will read it. A relative source is a volume name
        // to a container runtime, and resolving it here is also what makes the check below ask
        // about the same file the bind will use.
        Path file = Path.of(path).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IllegalStateException("spire.run.ca-bundle-path is set to \"" + path
                    + "\", which is not a readable file on this host. A missing bind source is"
                    + " not an error in every runtime -- it can be created as an empty directory --"
                    + " so every TLS call in every run would fail against it. The path must exist"
                    + " on the machine the container runtime runs on, at this same path.");
        }
        requireCertificateBundle(file);
        return file.toString();
    }

    /**
     * The file must be a certificate bundle, and must not be anything more.
     *
     * <p>Two refusals, and the first is the one that matters. This file is mounted into the
     * container that runs untrusted model output at full shell access, and the combined
     * {@code server.pem} many corporate tools export is a PRIVATE KEY followed by its chain --
     * so pointing the setting at one hands the agent a private key. Nothing about the mount would
     * look wrong.
     *
     * <p>The second catches a JKS or PKCS12 given by mistake: no certificate block at all, which
     * every consumer of the bundle would report as a handshake failure naming the forge.
     */
    private static void requireCertificateBundle(Path file) {
        String contents;
        try {
            contents = Files.readString(file, StandardCharsets.ISO_8859_1);
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException("spire.run.ca-bundle-path is set to " + file
                    + ", which could not be read: " + unreadable.getMessage(), unreadable);
        }
        if (PRIVATE_KEY_BLOCK.matcher(contents).find()) {
            throw new IllegalStateException("spire.run.ca-bundle-path is set to " + file
                    + ", which contains a PRIVATE KEY. This file is mounted into the container that"
                    + " runs untrusted model output; a trust bundle holds certificates only."
                    + " Export the certificate chain on its own.");
        }
        if (!contents.contains("-----BEGIN CERTIFICATE-----")) {
            throw new IllegalStateException("spire.run.ca-bundle-path is set to " + file
                    + ", which holds no PEM certificate. A keystore is not a bundle -- export the"
                    + " chain in PEM form, with the public roots appended.");
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
        List<String> missing = new ArrayList<>();
        if (host == null) {
            missing.add("registry-host");
        }
        if (username == null) {
            missing.add("registry-username");
        }
        if (secret == null) {
            missing.add("registry-secret");
        }
        if (missing.size() == 3) {
            return null;
        }
        if (!missing.isEmpty()) {
            // EVERY missing part, not the first. Naming one sends an operator round the loop once
            // per omission -- set the username, restart, be told about the secret -- and each lap
            // is a worker restart.
            throw new IllegalStateException("a private registry needs spire.run.registry-host,"
                    + " -username and -secret together; missing: " + String.join(", ", missing)
                    + ". A partial credential falls back to an anonymous pull that reports a"
                    + " private image as not found.");
        }
        return new RegistryCredential(host, username, secret);
    }

    /**
     * The configured value, or null when unset or blank.
     *
     * <p>Returns a {@code String} rather than taking an {@code Optional} parameter, which
     * {@code clean-code-java.md} forbids; every caller resolves to a plain value at the boundary.
     */
    private static String trimmed(Optional<String> value) {
        return value.map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
    }
}
