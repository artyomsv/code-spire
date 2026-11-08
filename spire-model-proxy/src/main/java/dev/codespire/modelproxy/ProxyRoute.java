package dev.codespire.modelproxy;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where the proxy forwards, how it injects the credential, and which paths it lets through.
 *
 * <p>The route comes from the harness adapter through the worker, never from the reviewed
 * repository, the ticket or the prompt. Every refusal names the variable and never its value: a
 * start failure is printed where {@code docker logs} shows it, and the neighbouring variable is the
 * credential.
 */
public record ProxyRoute(URI upstream, Injection injection, Set<String> exactPaths, List<String> pathPrefixes) {

    /** How the real credential is put on the outgoing request. */
    public enum Injection { BEARER, X_API_KEY, CHATGPT_SEAT }

    static final String UPSTREAM = "SPIRE_MODEL_UPSTREAM";
    static final String INJECTION = "SPIRE_MODEL_INJECTION";
    static final String PATHS_EXACT = "SPIRE_MODEL_PATHS_EXACT";
    static final String PATHS_PREFIX = "SPIRE_MODEL_PATHS_PREFIX";
    static final String SECRET = "SPIRE_MODEL_SECRET";
    static final String ACCOUNT = "SPIRE_MODEL_ACCOUNT";
    static final String ALLOW_PLAINTEXT_UPSTREAM = "SPIRE_MODEL_ALLOW_PLAINTEXT_UPSTREAM";

    public ProxyRoute {
        exactPaths = Set.copyOf(exactPaths);
        pathPrefixes = List.copyOf(pathPrefixes);
    }

    /**
     * Reads and checks the whole route. The secret and, for a seat, the account are checked for
     * presence here too, so a misconfigured unit fails at start rather than on its first request.
     */
    public static ProxyRoute fromEnvironment(Map<String, String> env) {
        URI upstream = upstream(env);
        Injection injection = injection(env);
        required(env, SECRET);
        if (injection == Injection.CHATGPT_SEAT) {
            required(env, ACCOUNT);
        }
        List<String> exact = paths(env, PATHS_EXACT);
        List<String> prefixes = paths(env, PATHS_PREFIX);
        if (exact.isEmpty() && prefixes.isEmpty()) {
            throw new IllegalArgumentException(PATHS_EXACT + " and " + PATHS_PREFIX
                    + " are both empty, so every request would be refused; declare the model paths");
        }
        return new ProxyRoute(upstream, injection, new LinkedHashSet<>(exact), prefixes);
    }

    static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is not set");
        }
        return value.strip();
    }

    private static URI upstream(Map<String, String> env) {
        URI uri;
        try {
            uri = new URI(required(env, UPSTREAM));
        } catch (URISyntaxException notAUri) {
            // Not chained: the parser's message quotes the input.
            throw new IllegalArgumentException(UPSTREAM + " is not a URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean plaintextAllowed = "true".equalsIgnoreCase(String.valueOf(env.get(ALLOW_PLAINTEXT_UPSTREAM)).strip());
        if (!scheme.equals("https") && !(scheme.equals("http") && plaintextAllowed)) {
            throw new IllegalArgumentException(UPSTREAM + " must be an https origin (http only with "
                    + ALLOW_PLAINTEXT_UPSTREAM + "=true, which is for tests)");
        }
        String path = uri.getRawPath();
        boolean originOnly = uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null && (path == null || path.isEmpty() || path.equals("/"));
        if (!originOnly) {
            throw new IllegalArgumentException(UPSTREAM + " must be an origin: scheme, host and port only");
        }
        int port = uri.getPort();
        return URI.create(scheme + "://" + uri.getHost() + (port == -1 ? "" : ":" + port));
    }

    private static Injection injection(Map<String, String> env) {
        String raw = required(env, INJECTION);
        try {
            return Injection.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            // Not chained: valueOf's message quotes the input.
            throw new IllegalArgumentException(INJECTION + " must be one of BEARER, X_API_KEY, CHATGPT_SEAT");
        }
    }

    private static List<String> paths(Map<String, String> env, String name) {
        String raw = env.get(name);
        List<String> paths = new ArrayList<>();
        if (raw == null) {
            return paths;
        }
        for (String entry : raw.split(",")) {
            String path = entry.strip();
            if (path.isEmpty()) {
                continue;
            }
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException(name + " holds an entry that does not start with /");
            }
            paths.add(path);
        }
        return paths;
    }
}
