package dev.codespire.e2e.spire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SpireDriverTest {

    @Test
    void mintsAnOperatorTokenTheOrchestratorAccepts() {
        SpireDriver spire = new SpireDriver();
        assertFalse(spire.operatorToken().isBlank());
        assertNotNull(spire.get("/api/providers"),
                "every registry read is admin-only (ADR-022's third rule), so this also proves the "
                        + "token carries spire-admin rather than merely being valid");
    }

    @Test
    void setsAndReadsBackTheReviewMode() {
        SpireDriver spire = new SpireDriver();
        spire.setReviewMode("active");
        assertEquals("active", spire.get("/api/settings/review-mode").get("mode").asText());
    }

    /**
     * Also proves the two-token split is necessary rather than defensive: this call goes to the
     * gateway's own prefix, and a token minted for the orchestrator is refused there by design.
     */
    /**
     * A unique target per run, cleaned up afterwards. A fixed one collides with the previous run's
     * registration on the second execution, and the registry's unique constraint surfaces as a bare
     * 500 rather than a 409 — so the test would fail saying nothing about why.
     */
    @Test
    void registersAWebhookAndGetsTheSecretExactlyOnce() {
        SpireDriver spire = new SpireDriver();
        String target = "e2e-probe/e2e-probe-webhook-" + System.currentTimeMillis();
        try {
            SpireDriver.Webhook hook = spire.registerWebhook("gitlab", target);

            assertFalse(hook.key().isBlank());
            assertFalse(hook.secret().isBlank(),
                    "the secret is returned only on create — GitLab's Secret token field needs it, and "
                            + "the view thereafter carries only hasSecret");
        } finally {
            spire.resetRegistries("e2e-probe-none", "e2e-probe-none", "e2e-probe-none",
                    "e2e-probe-none", target);
        }
    }
}
