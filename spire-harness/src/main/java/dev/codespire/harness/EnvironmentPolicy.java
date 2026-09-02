package dev.codespire.harness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What an arm may put in its child process's environment.
 *
 * <p><b>Delivering the prompt on stdin closed config override through argv. The environment is the
 * same door, one over.</b> {@code CODEX_HOME} relocates the file a harness reads its configuration
 * from and {@code OPENAI_BASE_URL} redirects the model endpoint — the precise outcome
 * {@link PromptDelivery} exists to prevent, reached without touching argv at all. {@code LD_PRELOAD},
 * {@code PATH} and {@code NODE_OPTIONS} go further and hijack the child process itself, which under
 * a container-is-the-boundary design (ADR-039) is running unconfined.
 *
 * <p>The credential map is operator-registered today (ADR-031), so none of this is reachable by a
 * work item yet. It becomes reachable the moment any repository-influenced value reaches the map,
 * which EXECUTION-LAYER §4.3's per-repo override ladder is heading towards — and the operator typo
 * case is live now: a mistyped key silently breaks a run with no signal.
 *
 * <p><b>A denylist, not an allowlist</b>, because each arm needs a different set of names and an
 * allowlist would have to enumerate every vendor's. And it <b>refuses</b> rather than dropping: a
 * credential an operator believes is set, which silently vanished, is worse than a run that will
 * not start.
 */
public final class EnvironmentPolicy {

    /**
     * Names, and name prefixes, no arm may accept from its credential map. Each either redirects
     * where the harness reads configuration or where it sends the model call, or replaces the
     * program the child actually executes.
     */
    private static final List<String> DENIED_PREFIXES = List.of(
            "LD_",              // LD_PRELOAD, LD_LIBRARY_PATH — replaces the child's code
            "DYLD_",            // the macOS equivalent
            "NODE_OPTIONS",     // --require injects a module into a Node harness
            "PYTHONPATH",       // the same for a Python one
            "PYTHONSTARTUP",
            "PATH",             // also PATHEXT: chooses which binary "codex" resolves to
            "HOME",             // relocates every dotfile the harness reads
            "SHELL",
            "BASH_ENV",
            "ENV",
            "CODEX_HOME",       // relocates config.toml, which -c was blocked from editing
            "CLAUDE_CONFIG_DIR",
            "XDG_CONFIG_HOME",
            "GIT_",             // GIT_SSH_COMMAND and friends execute
            "OPENAI_BASE_URL",  // redirects the endpoint, and the credential with it
            "ANTHROPIC_BASE_URL",
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "ALL_PROXY",
            "NO_PROXY");

    private EnvironmentPolicy() {
    }

    /**
     * Merges an arm's own settings over an invocation's credentials, refusing anything that would
     * hijack the child or silently overwrite a value the operator set.
     *
     * @param reserved names the arm sets itself; a credential using one is a collision, not an
     *                 override, and which side won would be invisible in the registry
     * @throws IllegalArgumentException naming the offending key
     */
    public static Map<String, String> merge(Map<String, String> credentials,
                                            Map<String, String> reserved) {
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : credentials.entrySet()) {
            String key = entry.getKey();
            requireAcceptable(key, reserved.keySet());
            env.put(key, entry.getValue());
        }
        env.putAll(reserved);
        return Map.copyOf(env);
    }

    private static void requireAcceptable(String key, Set<String> reserved) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("an environment variable must have a name");
        }
        if (key.indexOf('=') >= 0 || key.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("illegal character in environment name: " + key);
        }
        if (reserved.contains(key)) {
            throw new IllegalArgumentException(key + " is set by the harness adapter itself; a "
                    + "credential of the same name would be silently discarded or silently win");
        }
        String upper = key.toUpperCase(Locale.ROOT);
        for (String denied : DENIED_PREFIXES) {
            if (upper.startsWith(denied)) {
                throw new IllegalArgumentException(key + " is refused: it would change where the "
                        + "harness reads its configuration, where it sends the model call, or which "
                        + "program the child actually runs");
            }
        }
    }
}
