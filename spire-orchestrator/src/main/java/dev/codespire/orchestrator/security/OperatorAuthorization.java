package dev.codespire.orchestrator.security;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.security.spi.runtime.AuthorizationController;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Alternative;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The operator-authentication switch (D10).
 *
 * <p>Turning authorization off is expressed here rather than by disabling OIDC, because disabling
 * OIDC leaves the {@code @RolesAllowed} annotations compiled in with no identity to satisfy them —
 * every endpoint would answer 401 instead of opening, so the escape hatch would break the stack
 * rather than unlock it.
 *
 * <p>This controller governs {@code quarkus.http.auth.permission.*} policies as well as annotation
 * checks — verified in the phase-0 spike, where disabling it took a REST call from 302 to 200 and a
 * WebSocket upgrade from 302 to 101. That matters: the socket upgrade is secured by a permission
 * policy, so a switch that reached only annotations would leave the stack half-authenticated, with
 * requests open and sockets still challenging.
 *
 * <p><b>Off is a development-only state.</b> Outside {@code %dev}/{@code %test} the service refuses
 * to start rather than warning: a config flag that silently runs an internet-facing edge with no
 * authentication is the exact failure D10 exists to prevent, and a warning in a log nobody reads is
 * not a control.
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
                LOG.warnf("spire.security.auth-enabled=false — the operator API and its WebSocket "
                        + "are UNAUTHENTICATED. Permitted only in %s.", mode);
            }
            return;
        }
        throw new IllegalStateException(REFUSAL);
    }

    static final String REFUSAL =
            "spire.security.auth-enabled=false is not permitted outside dev/test. This service serves "
                    + "the provider registry, the event store and the dead-letter queue. "
                    + "Unset it, or configure an OIDC provider via "
                    + "SPIRE_OIDC_AUTH_SERVER_URL.";

    @ConfigProperty(name = "quarkus.http.proxy.proxy-address-forwarding", defaultValue = "false")
    boolean proxyAddressForwarding;

    /**
     * Empty when unset — and also when its {@code ${SPIRE_TRUSTED_PROXIES}} expression cannot be
     * resolved, because an {@link java.util.Optional} config value swallows an unresolvable expression
     * instead of failing. That is precisely why this guard exists rather than a no-default expression:
     * the service was observed starting cleanly with the variable absent, leaving forwarded headers
     * trusted from anywhere.
     */
    @ConfigProperty(name = "quarkus.http.proxy.trusted-proxies")
    java.util.Optional<String> trustedProxies;

    /**
     * Forwarding and a trusted-proxy list are only safe together.
     *
     * <p>{@code proxy-address-forwarding} makes the service believe {@code X-Forwarded-For} and
     * {@code -Proto}. Unrestricted, anything that can reach this port can therefore forge its apparent
     * client address and scheme — the vector {@code docs/D10-AUTH-PLAN.md} names when it records the
     * setting as safe "for the future edge only". Refusing here rather than warning follows the same
     * reasoning as the switch above: the deploy artifacts do enforce the pairing, but a hand-rolled
     * {@code docker run} does not, and the unsafe combination must not be silently runnable.
     */
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

    /**
     * Pure so it can be tested directly, like {@link #isPermitted}.
     *
     * <p>Presence is not width. A list only restricts anything if it excludes something, and a
     * zero-length prefix matches every address — so {@code SPIRE_TRUSTED_PROXIES=0.0.0.0/0} satisfied
     * a not-blank check while re-opening precisely what the pairing exists to close. That matters
     * more since the services began honouring {@code X-Forwarded-Host}: the trust check is the only
     * thing standing between a forged Host and the OIDC redirect_uri built from it.
     *
     * <p>Judged by prefix length rather than by the two well-known spellings, because
     * {@code 10.0.0.0/0} matches everything just as thoroughly.
     */
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

    /**
     * Pure so it can be tested directly.
     *
     * <p>The container-level behaviour cannot be: {@code quarkus-test-security} contributes its own
     * {@link AuthorizationController} that outranks this one on the test classpath, so a test that
     * drives HTTP measures Quarkus's test controller rather than this class. The phase-0 spike
     * verified the real path instead — with the flag off, a guarded request went from 302 to 200 and
     * a WebSocket upgrade from 302 to 101.
     */
    static boolean isPermitted(boolean authEnabled, LaunchMode mode) {
        return authEnabled || mode == LaunchMode.DEVELOPMENT || mode == LaunchMode.TEST;
    }
}
