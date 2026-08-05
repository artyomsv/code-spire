package dev.codespire.gateway.security;

import io.quarkus.runtime.LaunchMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule behind the development escape hatch: running unauthenticated is a dev/test state, and
 * anywhere else the service must refuse to start rather than warn. A flag that silently leaves an
 * internet-facing edge open is the exact failure this work exists to prevent, and a warning in a log
 * nobody reads is not a control.
 *
 * <p>Deliberately a plain unit test rather than a {@code @QuarkusTest}. The container-level
 * behaviour is not observable from the test classpath: {@code quarkus-test-security} contributes its
 * own {@code AuthorizationController} which outranks the application's, so injecting one resolves
 * Quarkus's {@code TestAuthController} and measures the framework, not this class. Confusing those
 * two produced a false conclusion once already — the toggle looked broken when what was really
 * happening is that the test framework had replaced it.
 */
class OperatorAuthorizationTest {

    @Test
    void authenticationOnIsPermittedEverywhere() {
        for (LaunchMode mode : LaunchMode.values()) {
            assertTrue(OperatorAuthorization.isPermitted(true, mode), "auth on should be fine in " + mode);
        }
    }

    @Test
    void authenticationOffIsPermittedOnlyWhileDeveloping() {
        assertTrue(OperatorAuthorization.isPermitted(false, LaunchMode.DEVELOPMENT));
        assertTrue(OperatorAuthorization.isPermitted(false, LaunchMode.TEST));
    }

    /** The one that matters: a packaged, running service must not start unauthenticated. */
    @Test
    void authenticationOffIsRefusedInProduction() {
        assertFalse(OperatorAuthorization.isPermitted(false, LaunchMode.NORMAL));
    }

    // ---- forwarded-header trust: the two settings are only safe together ----

    /**
     * The case this rule exists for, and it is not hypothetical: the prod profile reads
     * {@code trusted-proxies} from {@code ${SPIRE_TRUSTED_PROXIES}}, and an {@code Optional} config
     * value swallows an unresolvable expression rather than failing. The service was observed starting
     * cleanly with the variable absent — forwarding enabled, nothing restricting it — so an
     * expression with no default is not a control here and this check is.
     */
    @Test
    void forwardingWithNoTrustedProxiesIsRefused() {
        assertFalse(OperatorAuthorization.isForwardingSafe(true, ""));
        assertFalse(OperatorAuthorization.isForwardingSafe(true, "   "));
    }

    @Test
    void forwardingWithATrustedProxyIsFine() {
        assertTrue(OperatorAuthorization.isForwardingSafe(true, "10.244.0.0/16"));
    }

    /** Nothing to spoof when the service does not believe forwarded headers in the first place. */
    @Test
    void notForwardingNeedsNoTrustedProxies() {
        assertTrue(OperatorAuthorization.isForwardingSafe(false, ""));
    }
}
