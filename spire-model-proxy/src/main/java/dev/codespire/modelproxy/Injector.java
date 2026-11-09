package dev.codespire.modelproxy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The headers sent to the vendor: the agent's, minus every credential and transport header, plus the run's real
 * credential, and a request for an unencoded answer.
 *
 * <p>Every credential header is dropped whatever the injection kind, so a placeholder the agent sent under another
 * scheme's name never travels next to the real one. {@code Accept-Encoding} is always {@code identity}: the answer is
 * searched for an echo of the credential as it streams back ({@link EchoMask}), which a compressed body would hide.
 */
final class Injector {

    static final String AUTHORIZATION = "authorization";
    static final String X_API_KEY = "x-api-key";
    static final String CHATGPT_ACCOUNT_ID = "chatgpt-account-id";
    static final String ACCEPT_ENCODING = "accept-encoding";
    static final String IDENTITY = "identity";

    private static final Set<String> CREDENTIAL_HEADERS =
            Set.of(AUTHORIZATION, X_API_KEY, CHATGPT_ACCOUNT_ID, "cookie", "proxy-authorization");

    /**
     * The JDK client's restricted headers (it sets them itself and refuses them from a caller), and
     * the hop-by-hop headers, which belong to the agent's connection and not the vendor's.
     */
    static final Set<String> TRANSPORT_HEADERS = Set.of("connection", "content-length", "expect", "host",
            "upgrade", "keep-alive", "proxy-connection", "proxy-authenticate", "te", "trailer", "transfer-encoding");

    private Injector() {
    }

    static Map<String, List<String>> outgoing(Map<String, List<String>> incoming, Credential credential) {
        Set<String> namedByConnection = namedByConnection(incoming);
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        incoming.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (CREDENTIAL_HEADERS.contains(lower) || TRANSPORT_HEADERS.contains(lower)
                    || namedByConnection.contains(lower)) {
                return;
            }
            outgoing.computeIfAbsent(lower, k -> new ArrayList<>()).addAll(values);
        });
        outgoing.put(ACCEPT_ENCODING, List.of(IDENTITY));
        inject(outgoing, credential);
        return outgoing;
    }

    private static void inject(Map<String, List<String>> outgoing, Credential credential) {
        switch (credential.injection()) {
            case BEARER -> outgoing.put(AUTHORIZATION, List.of("Bearer " + credential.secret()));
            case X_API_KEY -> outgoing.put(X_API_KEY, List.of(credential.secret()));
            case CHATGPT_SEAT -> {
                outgoing.put(AUTHORIZATION, List.of("Bearer " + credential.secret()));
                outgoing.put(CHATGPT_ACCOUNT_ID, List.of(credential.account()));
            }
        }
    }

    /** The header names a {@code Connection} header lists, which are hop-by-hop by declaration. */
    static Set<String> namedByConnection(Map<String, List<String>> headers) {
        Set<String> named = new HashSet<>();
        headers.forEach((name, values) -> {
            if (!name.equalsIgnoreCase("connection")) {
                return;
            }
            for (String value : values) {
                for (String token : value.split(",")) {
                    named.add(token.strip().toLowerCase(Locale.ROOT));
                }
            }
        });
        return named;
    }
}
