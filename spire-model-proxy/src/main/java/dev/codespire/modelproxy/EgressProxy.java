package dev.codespire.modelproxy;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.util.Map;

/** The CONNECT proxy for the vendor connection, from {@code HTTPS_PROXY}. */
final class EgressProxy {

    static final String HTTPS_PROXY = "HTTPS_PROXY";

    private EgressProxy() {
    }

    static ProxySelector selector(Map<String, String> env) {
        String raw = env.get(HTTPS_PROXY);
        if (raw == null || raw.isBlank()) {
            raw = env.get("https_proxy");
        }
        if (raw == null || raw.isBlank()) {
            return HttpClient.Builder.NO_PROXY;
        }
        String authority = raw.strip();
        int scheme = authority.indexOf("://");
        authority = scheme < 0 ? authority : authority.substring(scheme + 3);
        int slash = authority.indexOf('/');
        authority = slash < 0 ? authority : authority.substring(0, slash);
        if (authority.contains("@")) {
            throw new IllegalArgumentException(HTTPS_PROXY + " carries a credential, which the model proxy never"
                    + " sends; the run unit's egress proxy takes none");
        }
        int colon = authority.lastIndexOf(':');
        int port = colon <= 0 ? -1 : portOf(authority.substring(colon + 1));
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(HTTPS_PROXY + " must be http://host:port");
        }
        return ProxySelector.of(new InetSocketAddress(authority.substring(0, colon), port));
    }

    private static int portOf(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException notAPort) {
            return -1;
        }
    }
}
