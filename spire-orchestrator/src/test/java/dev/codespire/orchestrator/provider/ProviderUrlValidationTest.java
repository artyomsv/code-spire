package dev.codespire.orchestrator.provider;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The SSRF guard on provider baseUrl (CWE-918): create/update whoami()s the URL
 * server-side, so with the strict mode (the fail-closed default —
 * spire.security.allow-insecure-provider-urls=false) only https URLs resolving
 * to public addresses pass. Literal IPs are used so no DNS lookup happens in the
 * test. The relaxed mode (%dev/%test) admits the localhost WireMock SCMs but
 * still requires a parseable absolute URL.
 */
class ProviderUrlValidationTest {

    private static ProviderResource strict() {
        ProviderResource resource = new ProviderResource();
        resource.allowInsecureProviderUrls = false;
        return resource;
    }

    private static ProviderResource relaxed() {
        ProviderResource resource = new ProviderResource();
        resource.allowInsecureProviderUrls = true;
        return resource;
    }

    @Test
    void httpsToAPublicAddressPasses() {
        assertDoesNotThrow(() -> strict().validateBaseUrl("https://8.8.8.8/api/v3"));
    }

    @Test
    void plainHttpIsRejected() {
        assertThrows(BadRequestException.class, () -> strict().validateBaseUrl("http://8.8.8.8"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1",          // loopback
            "https://169.254.169.254",    // link-local / cloud metadata
            "https://10.20.30.40",        // RFC1918 site-local
            "https://192.168.1.10",       // RFC1918 site-local
            "https://172.16.0.1",         // RFC1918 site-local
            "https://100.100.100.200",    // 100.64/10 carrier-NAT (cloud metadata)
            "https://0.0.0.0",            // any-local
            "https://[::1]",              // IPv6 loopback
            "https://[fd00::1]",          // IPv6 unique-local fc00::/7
            "https://[fe80::1]"           // IPv6 link-local
    })
    void internalAddressesAreRejected(String url) {
        assertThrows(BadRequestException.class, () -> strict().validateBaseUrl(url),
                url + " must be rejected by the SSRF guard");
    }

    @Test
    void unparseableUrlIsRejected() {
        assertThrows(BadRequestException.class, () -> strict().validateBaseUrl("ht tp://not a url"));
    }

    @Test
    void relativeUrlWithoutHostIsRejectedEvenWhenRelaxed() {
        assertThrows(BadRequestException.class, () -> relaxed().validateBaseUrl("/just/a/path"));
    }

    @Test
    void relaxedModeAllowsLocalhostHttpForDevAndTestWireMocks() {
        assertDoesNotThrow(() -> relaxed().validateBaseUrl("http://localhost:38080"));
        assertDoesNotThrow(() -> relaxed().validateBaseUrl("http://127.0.0.1:38080"));
    }
}
