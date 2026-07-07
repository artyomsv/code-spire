package dev.codespire.orchestrator.security;

import jakarta.ws.rs.BadRequestException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * SSRF guard (CWE-918) for operator-supplied base URLs the orchestrator
 * dereferences server-side (SCM whoami, LLM key validation). A URL must be an
 * absolute https URL resolving to a public address — loopback, link-local (cloud
 * metadata 169.254.169.254), RFC1918, carrier-NAT 100.64/10 and IPv6 unique-local
 * fc00::/7 are all rejected. The relaxed mode (%dev/%test) only requires a
 * parseable absolute URL, for the http://localhost WireMock/dev endpoints.
 */
public final class PublicHttpsGuard {

    private PublicHttpsGuard() {
    }

    /** @throws BadRequestException if the URL is unsafe to dereference server-side. */
    public static void validate(String baseUrl, boolean allowInsecure) {
        URI uri;
        try {
            uri = new URI(baseUrl == null ? "" : baseUrl.trim());
        } catch (URISyntaxException e) {
            throw new BadRequestException("baseUrl is not a valid URL");
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new BadRequestException("baseUrl must be an absolute http(s) URL with a host");
        }
        if (allowInsecure) {
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BadRequestException("baseUrl must use https");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new BadRequestException("baseUrl host cannot be resolved");
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new BadRequestException("baseUrl must resolve to a public address");
            }
        }
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false; // loopback, 0.0.0.0/::, 169.254/fe80 (cloud metadata), RFC1918
        }
        byte[] raw = address.getAddress();
        if (raw.length == 16 && (raw[0] & 0xFE) == 0xFC) {
            return false; // fc00::/7 unique-local
        }
        // 100.64.0.0/10 carrier-grade NAT — used for metadata endpoints by some clouds
        return !(raw.length == 4 && (raw[0] & 0xFF) == 100 && (raw[1] & 0xC0) == 64);
    }
}
