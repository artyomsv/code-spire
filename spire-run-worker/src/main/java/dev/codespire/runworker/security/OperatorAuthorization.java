package dev.codespire.runworker.security;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.security.spi.runtime.AuthorizationController;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Alternative;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * The operator-authentication posture every deployable carries (ADR-022), on the run worker.
 *
 * <p>The worker serves nothing but health today, and that is exactly when this is cheapest to get
 * right: the invariant is that an endpoint added later is <em>denied</em> by default rather than
 * public, and that {@code proxy-address-forwarding} cannot be switched on without naming the proxy
 * it trusts. The other three services learned both the hard way; this one starts with them.
 *
 * <p>Same two refusals as the review worker's, with the message saying what THIS service holds: a
 * dispatched run's command carries a machine-account write credential and a model key, and the
 * worker holds the keyset that opens them.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class OperatorAuthorization extends AuthorizationController {

    private static final Logger LOG = Logger.getLogger(OperatorAuthorization.class);

    @ConfigProperty(name = "spire.security.auth-enabled", defaultValue = "true")
    boolean authEnabled;

    @Override
    public boolean isAuthorizationEnabled() {
        return authEnabled;
    }

    void enforceProductionAuthentication(@Observes StartupEvent event) {
        LaunchMode mode = LaunchMode.current();
        if (isPermitted(authEnabled, mode)) {
            if (!authEnabled) {
                LOG.warnf("spire.security.auth-enabled=false — the run worker's HTTP surface is "
                        + "UNAUTHENTICATED. Permitted only in %s.", mode);
            }
            return;
        }
        throw new IllegalStateException(REFUSAL);
    }

    static final String REFUSAL =
            "spire.security.auth-enabled=false is not permitted outside dev/test. This service holds "
                    + "the keyset that opens each run's machine-account write credential and model key. "
                    + "Unset it, or configure an OIDC provider via SPIRE_OIDC_AUTH_SERVER_URL.";

    static boolean isPermitted(boolean authEnabled, LaunchMode mode) {
        return authEnabled || mode == LaunchMode.DEVELOPMENT || mode == LaunchMode.TEST;
    }

    @ConfigProperty(name = "quarkus.http.proxy.proxy-address-forwarding", defaultValue = "false")
    boolean proxyAddressForwarding;

    @ConfigProperty(name = "quarkus.http.proxy.trusted-proxies")
    Optional<String> trustedProxies;

    void enforceTrustedProxyPairing(@Observes StartupEvent event) {
        if (isForwardingSafe(proxyAddressForwarding, trustedProxies.orElse(""))) {
            return;
        }
        throw new IllegalStateException(FORWARDING_REFUSAL);
    }

    static final String FORWARDING_REFUSAL =
            "quarkus.http.proxy.proxy-address-forwarding=true with no usable "
                    + "quarkus.http.proxy.trusted-proxies: forwarded client addresses, schemes and hosts "
                    + "would be trusted from ANY source that can reach this port. Set SPIRE_TRUSTED_PROXIES "
                    + "to the network the dashboard proxy runs on (the pod or bridge CIDR), or turn "
                    + "forwarding off. A zero-length prefix (0.0.0.0/0, ::/0) is refused for the same "
                    + "reason as an empty value: it excludes nothing.";

    /** Presence is not enough: a zero-length prefix is as wide as no value at all. */
    static boolean isForwardingSafe(boolean forwarding, String trustedProxies) {
        if (!forwarding) {
            return true;
        }
        if (trustedProxies.isBlank()) {
            return false;
        }
        for (String entry : trustedProxies.split(",")) {
            if (entry.trim().endsWith("/0")) {
                return false;
            }
        }
        return true;
    }
}
