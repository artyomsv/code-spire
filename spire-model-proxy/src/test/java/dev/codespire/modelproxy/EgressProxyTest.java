package dev.codespire.modelproxy;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every proxied run sets {@code HTTPS_PROXY} to the unit's egress proxy, so this parse is on the path
 * of every vendor call. A value it cannot honour refuses at start naming the variable, never echoing
 * the value: a proxy URL may carry a credential.
 */
class EgressProxyTest {

    private static final URI VENDOR = URI.create("https://api.openai.com/v1/responses");

    private static InetSocketAddress proxyOf(Map<String, String> env) {
        ProxySelector selector = EgressProxy.selector(env);
        List<Proxy> chosen = selector.select(VENDOR);
        assertEquals(1, chosen.size(), chosen.toString());
        assertEquals(Proxy.Type.HTTP, chosen.get(0).type(), chosen.toString());
        return (InetSocketAddress) chosen.get(0).address();
    }

    private static void refused(String value, String... absent) {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> EgressProxy.selector(Map.of("HTTPS_PROXY", value)));
        assertTrue(refusal.getMessage().contains("HTTPS_PROXY"), refusal.getMessage());
        for (String part : absent) {
            assertFalse(refusal.getMessage().contains(part), "the refusal echoes the value: " + refusal.getMessage());
        }
    }

    @Test
    void aUrlWithASchemeIsRead() {
        InetSocketAddress address = proxyOf(Map.of("HTTPS_PROXY", "http://egress:3128"));

        assertEquals("egress", address.getHostString());
        assertEquals(3128, address.getPort());
    }

    @Test
    void aHostAndPortWithoutASchemeIsRead() {
        InetSocketAddress address = proxyOf(Map.of("HTTPS_PROXY", "egress:3128"));

        assertEquals("egress", address.getHostString());
        assertEquals(3128, address.getPort());
    }

    @Test
    void aTrailingSlashIsIgnored() {
        assertEquals(3128, proxyOf(Map.of("HTTPS_PROXY", "http://egress:3128/")).getPort());
    }

    @Test
    void anIpv6LiteralIsRead() {
        InetSocketAddress address = proxyOf(Map.of("HTTPS_PROXY", "http://[::1]:3128"));

        assertTrue(address.getAddress() != null && address.getAddress().isLoopbackAddress(), address.toString());
        assertEquals(3128, address.getPort());
    }

    @Test
    void theLowerCaseNameIsTheFallback() {
        assertEquals("egress-lower", proxyOf(Map.of("https_proxy", "http://egress-lower:3128")).getHostString());
        assertEquals("egress-upper", proxyOf(Map.of("HTTPS_PROXY", "http://egress-upper:3128",
                "https_proxy", "http://egress-lower:3128")).getHostString());
        assertEquals("egress-lower", proxyOf(Map.of("HTTPS_PROXY", " ",
                "https_proxy", "http://egress-lower:3128")).getHostString());
    }

    @Test
    void noProxyMeansDirect() {
        assertEquals(List.of(Proxy.NO_PROXY), EgressProxy.selector(Map.of()).select(VENDOR));
        assertEquals(List.of(Proxy.NO_PROXY), EgressProxy.selector(Map.of("HTTPS_PROXY", "")).select(VENDOR));
    }

    /** No default port: the runtime always names one, and a guessed port would reach the wrong listener. */
    @Test
    void aMissingPortIsRefused() {
        refused("http://egress");
        refused("egress");
        refused("http://egress:");
        refused("http://[::1]");
    }

    @Test
    void aPortThatIsNotAPortIsRefused() {
        refused("http://egress:TEST-port", "TEST-port");
        refused("http://egress:0");
        refused("http://egress:70000");
    }

    @Test
    void aCredentialInTheUrlIsRefusedWithoutEchoingIt() {
        refused("http://svc:TEST-pass@egress:3128", "svc", "TEST-pass");
        refused("svc:TEST-pass@egress:3128", "svc", "TEST-pass");
    }
}
