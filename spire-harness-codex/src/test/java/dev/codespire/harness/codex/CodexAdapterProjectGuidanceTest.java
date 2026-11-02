package dev.codespire.harness.codex;

import dev.codespire.harness.HarnessInvocation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codex loads {@code AGENTS.md} and {@code AGENTS.override.md} from its working directory at startup. A review run's
 * working directory is the pull request's head, so a change could hand the reviewer its own instructions (review of PR
 * #368, security/H-1; docs/REPO-RULES.md: the commit under review must not rewrite the instructions reviewing it). A
 * build run works in the operator's own repository and keeps loading them.
 *
 * <p>Kept beside {@link CodexAdapterTest} rather than in it: that class is already past the 300-line limit.
 */
class CodexAdapterProjectGuidanceTest {

    private final CodexAdapter adapter = new CodexAdapter();

    @Test
    void aReviewRunLoadsNoProjectGuidance() {
        String script = script(adapter.command(invocation(HarnessInvocation.ProjectGuidance.IGNORED)));

        assertTrue(script.contains(" -c project_doc_max_bytes=0"), script);
        assertTrue(script.indexOf("project_doc_max_bytes=0") < script.indexOf(" -C "),
                "a config override, before the workspace and the prompt: " + script);
    }

    @Test
    void aBuildRunStillLoadsProjectGuidance() {
        String script = script(adapter.command(invocation(HarnessInvocation.ProjectGuidance.LOADED)));

        assertFalse(script.contains("project_doc"), script);
    }

    /** The invocation every caller written before the option existed builds: it loads the repository's guidance. */
    @Test
    void anInvocationWithoutTheOptionLoadsProjectGuidance() {
        HarnessInvocation plain = new HarnessInvocation("run_abc", "review the change", "/workspace", "gpt-5.6",
                Map.of(HarnessInvocation.CREDENTIAL, "sk-secret"), Duration.ofMinutes(30), "high");

        assertFalse(script(adapter.command(plain)).contains("project_doc"));
    }

    private static HarnessInvocation invocation(HarnessInvocation.ProjectGuidance guidance) {
        return new HarnessInvocation("run_abc", "review the change", "/workspace", "gpt-5.6",
                Map.of(HarnessInvocation.CREDENTIAL, "sk-secret"), Duration.ofMinutes(30), null, guidance);
    }

    private static String script(List<String> argv) {
        return argv.get(2);
    }
}
