package dev.codespire.modelproxy;

import com.sun.net.httpserver.Headers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which of the vendor's response headers reach the agent: a fixed list, and of those only a value that carries neither
 * the credential nor a seat's account id. Everything else stays with the proxy, whatever the status.
 *
 * <p>What passes, and why:
 *
 * <ul>
 *   <li>{@code content-type}: Codex reads a stream as a stream by it ({@code text/event-stream});</li>
 *   <li>{@code cache-control}: a stream says {@code no-cache} and nothing downstream should keep it;</li>
 *   <li>{@code retry-after}, {@code retry-after-ms}: how long a 429 or 503 asks the client to wait;</li>
 *   <li>{@code x-request-id}, {@code request-id}, {@code openai-request-id}, {@code cf-ray}: the ids a vendor's
 *       support asks for, which Codex prints with an error;</li>
 *   <li>{@code openai-model}, {@code openai-version}, {@code openai-processing-ms}: what answered and how long it took;
 *       </li>
 *   <li>names starting {@code x-ratelimit-}: an API key's request and token budget;</li>
 *   <li>names starting {@code x-codex-}: a seat's usage windows and credits (codex-cli reads its rate-limit snapshot
 *       from these).</li>
 * </ul>
 *
 * <p>Never {@code location} (a redirect could carry the credential, and the proxy follows none), {@code set-cookie},
 * {@code www-authenticate}, any hop-by-hop header, {@code content-length} or {@code content-encoding}: the body is
 * re-framed (chunked, or a fixed reply of the proxy's own) and only ever unencoded. A header the list leaves out that a
 * harness turns out to need is added here by name, with its reason.
 */
final class ResponseHeaders {

    private static final Set<String> ALLOWED = Set.of("content-type", "cache-control", "retry-after", "retry-after-ms",
            "x-request-id", "request-id", "openai-request-id", "cf-ray", "openai-model", "openai-version",
            "openai-processing-ms");

    private static final List<String> ALLOWED_PREFIXES = List.of("x-ratelimit-", "x-codex-");

    private ResponseHeaders() {
    }

    /** Copies the allowed headers of {@code incoming} whose values hold no echo. */
    static void copy(Map<String, List<String>> incoming, Headers outgoing, Credential credential) {
        incoming.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!isAllowed(lower)
                    || values.stream().anyMatch(credential::appearsIn)) {
                return;
            }
            outgoing.put(lower, new ArrayList<>(values));
        });
    }

    static boolean isAllowed(String lowerCaseName) {
        return ALLOWED.contains(lowerCaseName) || ALLOWED_PREFIXES.stream().anyMatch(lowerCaseName::startsWith);
    }
}
