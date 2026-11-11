package dev.codespire.harness.claudecode;

import dev.codespire.harness.CredentialRoute;
import dev.codespire.harness.EnvironmentPolicy;
import dev.codespire.harness.HarnessInvocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What Claude Code needs to run behind the unit's model proxy: the environment it gets in place of the key, and the
 * route the proxy forwards by. Measured against Claude Code 2.1.319 on 2026-11-11 (harness reviews 3b, Task 3): with
 * the placeholder key and the proxy's base URL the CLI reported {@code apiKeySource "ANTHROPIC_API_KEY"}, and the proxy
 * saw only {@code POST /v1/messages}.
 */
final class ClaudeCodeModelProxy {

    /** What a proxied run's key is. The model proxy drops the header the agent sends and injects the real key. */
    static final String PLACEHOLDER = "TEST-PLACEHOLDER";

    /** The variable the CLI reads its key from. */
    static final String API_KEY_VARIABLE = "ANTHROPIC_API_KEY";

    /**
     * The variable the CLI reads its endpoint from. On {@link EnvironmentPolicy}'s deny list for the credential map, so
     * it is set here among the adapter's own settings, as {@code CodexModelProxy} sets its own.
     */
    static final String BASE_URL_VARIABLE = "ANTHROPIC_BASE_URL";

    /** The CLI's update checks, telemetry and error reporting off: the only traffic is the model call. */
    static final Map<String, String> OWN_SETTINGS = Map.of("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");

    /** The one path a measured review called. The model list is not read by a run. */
    private static final String MESSAGES = "/v1/messages";

    private ClaudeCodeModelProxy() {
    }

    /**
     * A proxied run's environment, built without reading the credential's value: the neutral key is dropped, every
     * other entry goes through {@link EnvironmentPolicy}, and the placeholder and the proxy's address are reserved.
     */
    static Map<String, String> environment(HarnessInvocation invocation) {
        Map<String, String> credentials = new LinkedHashMap<>(invocation.credentials());
        credentials.remove(HarnessInvocation.CREDENTIAL);
        Map<String, String> reserved = new LinkedHashMap<>(OWN_SETTINGS);
        reserved.put(API_KEY_VARIABLE, PLACEHOLDER);
        reserved.put(BASE_URL_VARIABLE, CredentialRoute.AGENT_FACING_ORIGIN);
        return EnvironmentPolicy.merge(credentials, reserved);
    }

    /** The messages path only, with the key as {@code x-api-key}; none for a run that is not proxied or holds a sign-in. */
    static Optional<CredentialRoute> route(HarnessInvocation invocation) {
        if (!invocation.credentialProxied() || invocation.credentials().containsKey(HarnessInvocation.SIGN_IN)) {
            return Optional.empty();
        }
        return Optional.of(new CredentialRoute("https://api.anthropic.com", CredentialRoute.Injection.X_API_KEY,
                Set.of(MESSAGES), List.of()));
    }
}
