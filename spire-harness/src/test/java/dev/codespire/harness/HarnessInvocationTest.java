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
}
