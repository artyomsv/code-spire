package dev.codespire.runworker;

import dev.codespire.harness.CredentialRoute;
import dev.codespire.harness.HarnessInvocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * What a proxied unit hands its model proxy, and the placeholders it hands the harness arm instead (harness reviews
 * step 3). The model proxy's environment contract is {@code spire-model-proxy}'s {@code dev.codespire.modelproxy.ProxyRoute}.
 *
 * <p>{@code SPIRE_MODEL_ALLOW_PLAINTEXT_UPSTREAM} is never set here: it exists for the model proxy's own tests, and a
 * unit that set it would send the real credential over plain HTTP.
 */
final class ModelProxyEnvironment {

    /**
     * What every credential value becomes in the invocation a proxied arm sees: same keys, so the arm still knows a
     * seat from a key, and no value it could leak.
     */
    static final String PLACEHOLDER = "SPIRE-MODEL-PROXY-HOLDS-THE-CREDENTIAL";

    static final String UPSTREAM = "SPIRE_MODEL_UPSTREAM";

    static final String INJECTION = "SPIRE_MODEL_INJECTION";

    static final String PATHS_EXACT = "SPIRE_MODEL_PATHS_EXACT";

    static final String PATHS_PREFIX = "SPIRE_MODEL_PATHS_PREFIX";

    static final String SECRET = "SPIRE_MODEL_SECRET";

    static final String ACCOUNT = "SPIRE_MODEL_ACCOUNT";

    private ModelProxyEnvironment() {
    }

    /** The same credential map with every value replaced by {@link #PLACEHOLDER}. */
    static Map<String, String> placeholders(Map<String, String> credentials) {
        Map<String, String> held = new LinkedHashMap<>();
        credentials.keySet().forEach(key -> held.put(key, PLACEHOLDER));
        return Map.copyOf(held);
    }

    /**
     * The model proxy's environment: the arm's route, and the real secret. A seat hands over only its access token and
     * account id, never the sign-in file.
     */
    static Map<String, String> of(CredentialRoute route, Map<String, String> credentials) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(UPSTREAM, route.upstreamOrigin());
        env.put(INJECTION, route.injection().name());
        env.put(PATHS_EXACT, String.join(",", new TreeSet<>(route.exactPaths())));
        env.put(PATHS_PREFIX, String.join(",", route.pathPrefixes()));
        if (route.injection() == CredentialRoute.Injection.CHATGPT_SEAT) {
            Credentials.SeatToken seat = Credentials.seatToken(required(credentials, HarnessInvocation.SIGN_IN));
            env.put(SECRET, seat.accessToken());
            env.put(ACCOUNT, seat.accountId());
        } else {
            env.put(SECRET, required(credentials, HarnessInvocation.CREDENTIAL));
        }
        return Map.copyOf(env);
    }

    private static String required(Map<String, String> credentials, String key) {
        String value = credentials.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a proxied run needs its model credential under " + key
                    + " and the command carries none, so its model proxy would inject nothing");
        }
        return value;
    }
}
