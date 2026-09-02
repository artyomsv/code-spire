package dev.codespire.harness;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessInvocationTest {

    private static HarnessInvocation invocation(Map<String, String> credentials) {
        return new HarnessInvocation("run-1", "fix the bug", "/workspace", "gpt-5-codex",
                credentials, Duration.ofMinutes(30));
    }

    @Test
    void toStringNeverPrintsACredential() {
        // A record's generated toString() prints every component, so `log.info("{}", invocation)`
        // would put the machine-account token in a log line. The name of a credential is useful
        // diagnostics; its value is never printed.
        HarnessInvocation invocation = invocation(Map.of("CODEX_API_KEY", "sk-live-do-not-print"));

        String rendered = invocation.toString();

        assertFalse(rendered.contains("sk-live-do-not-print"), "credential value leaked: " + rendered);
        assertTrue(rendered.contains("CODEX_API_KEY"), "the credential's NAME is useful diagnostics");
        assertTrue(rendered.contains("run-1"));
    }

    @Test
    void credentialsAreSnapshotted() {
        Map<String, String> mutable = new HashMap<>(Map.of("A", "1"));
        HarnessInvocation invocation = invocation(mutable);
        mutable.put("B", "2");

        assertEquals(1, invocation.credentials().size(), "credentials must not alias the caller's map");
    }

    @Test
    void aCredentialMapIsNeverNull() {
        assertThrows(NullPointerException.class, () -> invocation(null));
    }

    @Test
    void aFlagShapedModelOrWorkspaceIsRefused() {
        // Neither is an injection on its own: argv is a list, so "--model <value>" consumes exactly
        // one element and nothing re-splits it. Both are control, though — ADR-036 says a repository
        // may never select the model endpoint, and workspacePath chooses where an UNCONFINED agent
        // works. A flag-shaped value is a configuration fault that should fail before a container
        // starts rather than as a CLI parse error inside one.
        assertThrows(IllegalArgumentException.class, () -> new HarnessInvocation("r", "p",
                "/workspace", "--dangerously-bypass-approvals-and-sandbox", Map.of(), Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new HarnessInvocation("r", "p",
                "-c foo=bar", "gpt-5.6", Map.of(), Duration.ofMinutes(1)));
    }

    @Test
    void aBlankModelOrWorkspaceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new HarnessInvocation("r", "p",
                "/workspace", "  ", Map.of(), Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new HarnessInvocation("r", "p",
                "", "gpt-5.6", Map.of(), Duration.ofMinutes(1)));
    }

    @Test
    void aRelativeWorkspacePathIsRefused() {
        // It would resolve against whatever the child process happened to have as its working
        // directory, which nothing in the run unit guarantees.
        assertThrows(IllegalArgumentException.class, () -> new HarnessInvocation("r", "p",
                "workspace", "gpt-5.6", Map.of(), Duration.ofMinutes(1)));
    }

    @Test
    void anOrdinaryInvocationIsStillAccepted() {
        // Guards the guard: a validator that refused everything would pass every test above.
        HarnessInvocation ok = new HarnessInvocation("run_1", "fix it", "/workspace", "gpt-5.6",
                Map.of("OPENAI_API_KEY", "k"), Duration.ofMinutes(30));

        assertEquals("/workspace", ok.workspacePath());
        assertEquals("gpt-5.6", ok.model());
    }
}