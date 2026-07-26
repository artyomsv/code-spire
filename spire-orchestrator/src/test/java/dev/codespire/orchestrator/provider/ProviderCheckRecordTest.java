package dev.codespire.orchestrator.provider;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The outcome has to survive a round trip, since the panel reads it back on every poll. */
@QuarkusTest
class ProviderCheckRecordTest {

    @Inject
    ProviderRegistry registry;

    private ProviderView created() {
        return registry.create(new ProviderInput("TEST-provider", "stub", "https://scm.example.invalid",
                "TEST-WS-" + UUID.randomUUID(), "bearer", null, "TEST-SECRET", "acct-1", true,
                List.of(), "test-bot", null));
    }

    @Test
    void aNewProviderHasNeverBeenChecked() {
        ProviderView view = created();
        assertNull(view.lastCheckAt());
        assertNull(view.lastCheckOk());
        assertNull(view.lastCheckError());
    }

    @Test
    void aFailedCheckIsStoredWithItsDetail() {
        ProviderView view = created();
        registry.recordCheck(UUID.fromString(view.id()), false, "Authentication rejected (HTTP 401)");
        ProviderView reread = registry.get(UUID.fromString(view.id())).orElseThrow();
        assertNotNull(reread.lastCheckAt());
        assertFalse(reread.lastCheckOk());
        assertEquals("Authentication rejected (HTTP 401)", reread.lastCheckError());
    }

    /** Success must null the stored error, or a stale message outlives the failure it described. */
    @Test
    void aPassingCheckClearsThePreviousError() {
        ProviderView view = created();
        registry.recordCheck(UUID.fromString(view.id()), false, "Authentication rejected (HTTP 401)");
        registry.recordCheck(UUID.fromString(view.id()), true, null);
        ProviderView reread = registry.get(UUID.fromString(view.id())).orElseThrow();
        assertTrue(reread.lastCheckOk());
        assertNull(reread.lastCheckError());
    }
}
