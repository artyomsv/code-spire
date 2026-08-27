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
        assertTrue(OperatorAuthorization.isForwardingSafe(true, "10.244.0.0/16,192.168.0.0/16"));
        assertTrue(OperatorAuthorization.isForwardingSafe(true, " 10.244.0.0/16 , 172.17.0.0/16 "));
    }

    /**
     * Presence was never the property worth checking — width is. A zero-length prefix matches every
     * address, so this value satisfies a not-blank check while restricting nothing, and the service
     * would start looking correctly configured with forwarded addresses, schemes and hosts believed
     * from anywhere that can reach the port.
     */
    @Test
    void forwardingTrustedFromEverywhereIsRefused() {
        assertFalse(OperatorAuthorization.isForwardingSafe(true, "0.0.0.0/0"));
        assertFalse(OperatorAuthorization.isForwardingSafe(true, "::/0"));
        assertFalse(OperatorAuthorization.isForwardingSafe(true, " 0.0.0.0/0 "));
    }

    /** Any prefix length of zero, not the two well-known spellings: 10.0.0.0/0 is just as wide. */
    @Test
    void anyZeroLengthPrefixIsRefusedWhicheverAddressCarriesIt() {
        assertFalse(OperatorAuthorization.isForwardingSafe(true, "10.0.0.0/0"));
        assertFalse(OperatorAuthorization.isForwardingSafe(true, "::1/0"));
    }

    /** One wide entry defeats the whole list, so the list is only as narrow as its widest member. */
    @Test
    void oneWideEntryAmongNarrowOnesIsRefused() {
        assertFalse(OperatorAuthorization.isForwardingSafe(true, "10.244.0.0/16,0.0.0.0/0"));
        assertFalse(OperatorAuthorization.isForwardingSafe(true, "0.0.0.0/0, 10.244.0.0/16"));
    }

    /** A /0 inside a longer prefix length is not one: /10 and /30 are ordinary networks. */
    @Test
    void aPrefixLengthEndingInAZeroDigitIsNotAZeroPrefix() {
        assertTrue(OperatorAuthorization.isForwardingSafe(true, "10.0.0.0/10"));
        assertTrue(OperatorAuthorization.isForwardingSafe(true, "10.244.0.0/30"));
        assertTrue(OperatorAuthorization.isForwardingSafe(true, "2001:db8::/40"));
    }

    /** Nothing to spoof when the service does not believe forwarded headers in the first place. */
    @Test
    void notForwardingNeedsNoTrustedProxies() {
        assertTrue(OperatorAuthorization.isForwardingSafe(false, ""));
        assertTrue(OperatorAuthorization.isForwardingSafe(false, "0.0.0.0/0"));
    }
}
