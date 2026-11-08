package dev.codespire.harness;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Where a proxied run's model proxy forwards, how it puts the real credential on the request, and which paths it lets
 * through. The arm's knowledge, like {@link HarnessAdapter#egressHosts}: never the reviewed repository's, the ticket's
 * or the prompt's.
 *
 * @param upstreamOrigin the vendor origin, scheme and host only
 * @param exactPaths paths the proxy passes only when equal
 * @param pathPrefixes paths the proxy passes when they start with one of these
 */
public record CredentialRoute(String upstreamOrigin, Injection injection, Set<String> exactPaths,
                              List<String> pathPrefixes) {

    /**
     * Where the agent reaches the model proxy inside its unit. The harness module cannot see {@code spire-runtime}, so
     * the run worker asserts this equals {@code RunUnitSpec.MODEL_PROXY_ADDRESS}.
     */
    public static final String AGENT_FACING_ORIGIN = "http://spire-model:8080";

    /** How the real credential is put on the outgoing request; the names equal the model proxy's own. */
    public enum Injection { BEARER, X_API_KEY, CHATGPT_SEAT }

    public CredentialRoute {
        Objects.requireNonNull(upstreamOrigin, "upstreamOrigin");
        Objects.requireNonNull(injection, "injection");
        exactPaths = Set.copyOf(exactPaths);
        pathPrefixes = List.copyOf(pathPrefixes);
    }
}
